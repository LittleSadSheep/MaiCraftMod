// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.maiwithu.maicraft.ability.design.DesignEdits;
import org.maiwithu.maicraft.ability.design.DesignLimits;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/**
 * 设计存储：按游戏实例保存图纸，不按世界。图纸不含世界坐标，跨世界复用是常事；换一个实例不跟着走，
 * 要带走用 export。库文件在实例的 config/maicraft/designs.sqlite。每次修改新建一版并记父版本，不设数量上限。
 */
public final class DesignStore {

    public static final String FILE_NAME = "designs.sqlite";
    private static final String SCOPE = "design";
    private static final String INSTANCE = "instance";

    private final DocumentStore documents;

    public DesignStore(Path file) {
        documents = new DocumentStore(file);
    }

    /** 实例配置目录下的库文件位置。 */
    public static Path fileIn(Path configDirectory) {
        return configDirectory.resolve("maicraft").resolve(FILE_NAME);
    }

    /** 存一张新图纸；先校验并展开，坏图纸不会进库。 */
    public BuildingDesign create(JsonObject drawing) {
        DesignCompiler.validate(drawing);
        return save(drawing, null);
    }

    /** 按名合并修改，整体重校验，存成新的一版；父版本保留。 */
    public BuildingDesign update(String id, JsonObject edits) {
        BuildingDesign original = load(id);
        JsonObject merged = DesignEdits.apply(original.drawing(), edits);
        DesignCompiler.validate(merged);
        return save(merged, original.id());
    }

    /** 读一张图纸；编号不对或库里没有就拒绝。 */
    public BuildingDesign load(String id) {
        checkId(id);
        try {
            String json = documents.read(SCOPE, INSTANCE, id, DesignLimits.MAX_DRAWING_BYTES);
            if (json == null) throw new IllegalArgumentException("没有这张图纸：" + id);
            return decode(id, json);
        } catch (DocumentStore.OverBudget exceeded) {
            throw new IllegalArgumentException("图纸超过字节上限：" + id, exceeded);
        } catch (IOException failure) {
            throw new IllegalStateException("图纸读不出来：" + failure.getMessage(), failure);
        }
    }

    private BuildingDesign save(JsonObject drawing, String parent) {
        BuildingDesign design = new BuildingDesign(UUID.randomUUID().toString(), parent, drawing);
        JsonObject root = new JsonObject();
        root.addProperty("design_id", design.id());
        if (parent != null) root.addProperty("parent_design_id", parent);
        root.add("drawing", design.drawing());
        String json = root.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > DesignLimits.MAX_DRAWING_BYTES) {
            throw new IllegalArgumentException("图纸超过字节上限 " + DesignLimits.MAX_DRAWING_BYTES + "，拆开画");
        }
        try {
            // 新的一版是独立的记录；旧版本与父版本都保留，正在施工的那版不会被改掉。
            if (!documents.write(SCOPE, INSTANCE, design.id(), json, true)) throw new IOException("设计编号撞车");
        } catch (IOException failure) {
            throw new IllegalStateException("图纸存不进去：" + failure.getMessage(), failure);
        }
        return design;
    }

    private static BuildingDesign decode(String id, String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("design_id") || !id.equals(root.get("design_id").getAsString()) || !root.has("drawing") || !root.get("drawing").isJsonObject()) {
            throw new IllegalStateException("图纸记录损坏：" + id);
        }
        String parent = root.has("parent_design_id") ? root.get("parent_design_id").getAsString() : null;
        return new BuildingDesign(id, parent, root.getAsJsonObject("drawing"));
    }

    private static void checkId(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("design_id 要是 UUID：" + id);
        }
    }
}
