// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/**
 * 把已经决定的建筑位置、材料和施工要求保存到当前世界的文件里，供取消或重启后续建。
 * 不保存可信的“已完成百分比”；重新施工时仍要读取世界，判断哪些格子真正完成。
 */
public final class BuildProjectStore {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final StateIdentity identity;

    public BuildProjectStore(StateIdentity identity) { this.identity = identity; }

    public static Optional<BuildProjectStore> available() {
        return StateIdentity.resolve(Minecraft.getInstance()).map(BuildProjectStore::new);
    }

    public static BuildProjectStore current() {
        return available().orElseThrow(() -> new IllegalStateException("build project world is unavailable"));
    }

    public String save(String dimension, JsonObject arguments, List<BuildTaskRecord.Target> targets) {
        String id = UUID.randomUUID().toString();
        save(id, dimension, arguments, targets);
        return id;
    }

    // 同一编号可更新一次确定下来的材料方案；去掉原始 ops，保存完整目标，恢复时禁止重新换材料种类。
    public void save(String id, String dimension, JsonObject arguments, List<BuildTaskRecord.Target> targets) {
        if (dimension == null || dimension.isBlank() || targets.isEmpty())
            throw new IllegalArgumentException("a build project requires a dimension and concrete targets");
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("project_id", id);
        root.addProperty("world_key", identity.key());
        root.addProperty("dimension", dimension);
        JsonObject frozen = arguments.deepCopy();
        frozen.remove("ops");
        frozen.addProperty("project_id", id);
        frozen.addProperty("broaden_material_families", false);
        frozen.add("project_targets", BuildProjectTargets.encode(targets));
        root.add("arguments", frozen);
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("build project exceeds storage limit");
        Path file = file(id);
        // 先写同目录临时文件，再替换正式文件；系统不支持原子替换时退回普通替换。失败会报给调用者。
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.write(temporary, bytes);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("could not persist the frozen build project", failure);
        } finally {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    // 读取后核对版本、项目编号、世界和维度，再检查每格保存状态仍可表达；返回副本，调用方改参数不会改磁盘记录。
    public JsonObject load(String id, String dimension) {
        try {
            Path file = file(id);
            long size = Files.size(file);
            if (size <= 0 || size > MAX_BYTES) throw new IOException("invalid build project size");
            byte[] bytes;
            try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IOException("invalid build project size");
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("version").getAsInt() != 1 || !id.equals(root.get("project_id").getAsString())
                    || !identity.key().equals(root.get("world_key").getAsString()))
                throw new IllegalArgumentException("build project identity mismatch");
            if (!dimension.equals(root.get("dimension").getAsString()))
                throw new IllegalArgumentException("build project belongs to another dimension; return there to continue");
            JsonObject arguments = root.getAsJsonObject("arguments").deepCopy();
            if (!id.equals(arguments.get("project_id").getAsString()))
                throw new IllegalArgumentException("build project argument identity mismatch");
            BuildProjectTargets.decode(arguments.getAsJsonArray("project_targets"));
            arguments.addProperty("broaden_material_families", false);
            return arguments;
        } catch (IOException failure) {
            throw new IllegalArgumentException("build project is unavailable in this world: " + id, failure);
        }
    }

    // 只接受标准 UUID，按世界键分目录保存，不能把项目编号当成任意文件路径。
    private Path file(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id))
            throw new IllegalArgumentException("project_id must be a canonical UUID");
        return identity.directory().resolve("build-projects").resolve(identity.key()).resolve(id + ".json");
    }
}
