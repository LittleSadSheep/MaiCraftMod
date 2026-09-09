// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.SharedConstants;

/**
 * 把模型导出为可重复使用的 JSON 或原版 NBT。JSON 保留负偏移；NBT 的坐标先平移到最小角为零，并另记原偏移。
 */
public final class BuildingSceneExport {
    private BuildingSceneExport() {}

    public static Path write(String id, JsonObject blueprint, String format) {
        return write(Minecraft.getInstance().gameDirectory.toPath().resolve("schematics"), id, blueprint, format);
    }

    // 使用模型编号命名文件，重复导出同编号和格式会替换旧文件；先写临时文件，成功后才移到正式路径。
    public static Path write(Path directory, String id, JsonObject blueprint, String format) {
        java.util.UUID.fromString(id);
        if (!format.equals("json") && !format.equals("nbt")) throw new IllegalArgumentException("Export format must be json or nbt");
        Path target = directory.resolve("maicraft-scene-" + id + "." + format);
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, ".scene-", ".tmp");
            try {
                if (format.equals("json")) Files.writeString(temporary, BuildingSceneBlocks.export(blueprint).toString());
                else NbtIo.writeCompressed(structure(blueprint), temporary);
                try { Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(temporary); }
            return target;
        } catch (IOException failure) { throw new IllegalStateException("Could not export building model", failure); }
    }

    // 找所有格子的最小 x/y/z，供原版 NBT 平移和返回导入时应补的偏移。
    public static List<Integer> minimum(JsonObject blueprint) {
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        for (var value : blueprint.getAsJsonArray("blocks")) {
            var offset = value.getAsJsonObject().getAsJsonArray("offset");
            for (int i = 0; i < 3; i++) min[i] = Math.min(min[i], offset.get(i).getAsInt());
        }
        return List.of(min[0], min[1], min[2]);
    }

    // 先验证普通方块蓝图并补齐建造会改变的默认值，再按不同方块状态合并材料表。
    public static CompoundTag structure(JsonObject blueprint) {
        org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocument.validateWire(blueprint);
        blueprint = BuildingSceneBlocks.export(blueprint);
        List<Integer> min = minimum(blueprint);
        int[] size = {1, 1, 1};
        ListTag blocks = new ListTag(), palette = new ListTag();
        Map<CompoundTag, Integer> indices = new LinkedHashMap<>();
        for (var value : blueprint.getAsJsonArray("blocks")) {
            JsonObject cell = value.getAsJsonObject();
            if (cell.has("part") || cell.has("nbt") && !cell.getAsJsonObject("nbt").isEmpty())
                throw new IllegalArgumentException("Only ordinary model blocks can be exported");
            CompoundTag state = new CompoundTag();
            state.putString("Name", cell.get("block_id").getAsString());
            CompoundTag properties = new CompoundTag();
            if (cell.has("properties")) cell.getAsJsonObject("properties").entrySet()
                    .forEach(p -> properties.putString(p.getKey(), p.getValue().getAsString()));
            if (!properties.isEmpty()) state.put("Properties", properties);
            Integer index = indices.get(state);
            if (index == null) { index = palette.size(); indices.put(state, index); palette.add(state); }
            CompoundTag block = new CompoundTag(); ListTag position = new ListTag();
            for (int i = 0; i < 3; i++) {
                int coordinate = Math.subtractExact(cell.getAsJsonArray("offset").get(i).getAsInt(), min.get(i));
                position.add(IntTag.valueOf(coordinate)); size[i] = Math.max(size[i], coordinate + 1);
            }
            block.put("pos", position); block.putInt("state", index); blocks.add(block);
        }
        CompoundTag root = new CompoundTag(); ListTag dimensions = new ListTag();
        for (int axis : size) dimensions.add(IntTag.valueOf(axis));
        root.put("size", dimensions); root.put("palette", palette); root.put("blocks", blocks);
        root.put("entities", new ListTag());
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        // 传统蓝图加载器不会自动应用这个自定义偏移；重新导入时，调用者要把锚点加上返回的 minecraft_offset。
        root.putIntArray("maicraft_offset", min.stream().mapToInt(Integer::intValue).toArray());
        BlueprintFiles.validate(root);
        return root;
    }
}
