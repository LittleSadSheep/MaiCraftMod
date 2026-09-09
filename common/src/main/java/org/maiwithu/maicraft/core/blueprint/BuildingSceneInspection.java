// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Set;
import org.maiwithu.maicraft.core.integration.machine.MachinePlanningBudget;
import static org.maiwithu.maicraft.core.blueprint.BuildingSceneGeometry.*;

/**
 * 读取场景和单个对象的结构信息，并计算转到游戏坐标后的范围；不检查世界里是否已经建好。
 */
public final class BuildingSceneInspection {
    private static final int PAGE_SIZE = 10;
    private BuildingSceneInspection() {}

    // 先检查整个模型结构，再按每页十个对象返回；超过末页时返回空列表，而不是访问越界。
    public static JsonObject sceneInfo(JsonObject scene, int page) {
        BuildingSceneCompiler.validateWire(scene);
        if (page < 0) throw bad("scene page must be nonnegative");
        JsonArray objects = scene.getAsJsonArray("objects");
        JsonObject result = new JsonObject();
        result.addProperty("name", scene.has("name") ? scene.get("name").getAsString() : "Scene");
        result.addProperty("object_count", objects.size());
        result.addProperty("materials_count", scene.getAsJsonObject("materials").size());
        result.addProperty("coordinate_system", coordinateSystem(scene));
        result.addProperty("coordinate_space", "scene_local");
        result.addProperty("page", page); result.addProperty("page_size", PAGE_SIZE);
        long start = (long) page * PAGE_SIZE;
        Set<String> cutters = cutters(scene);
        JsonArray rows = new JsonArray();
        for (long i = start; i < Math.min(start + PAGE_SIZE, objects.size()); i++)
            rows.add(describe(scene, objects.get((int) i).getAsJsonObject(), cutters));
        result.add("objects", rows); result.addProperty("has_more", start + rows.size() < objects.size());
        return result;
    }

    public static JsonObject objectInfo(JsonObject scene, String name) {
        BuildingSceneCompiler.validateWire(scene);
        for (var element : scene.getAsJsonArray("objects")) {
            JsonObject object = element.getAsJsonObject();
            if (object.get("name").getAsString().equals(name)) return describe(scene, object, cutters(scene));
        }
        throw bad("Object not found: " + name);
    }

    // 补齐默认旋转、单位缩放和材料列表，标出对象是否只作开孔用途；范围是做布尔开孔之前的原始盒子范围。
    private static JsonObject describe(JsonObject scene, JsonObject object, Set<String> cutters) {
        JsonObject result = object.deepCopy();
        String name = object.get("name").getAsString();
        result.addProperty("type", "MESH");
        if (!result.has("primitive")) result.add("primitive", object.get("type").deepCopy());
        JsonArray rotation = object.has("rotation_euler") ? object.getAsJsonArray("rotation_euler").deepCopy() : triple(0, 0, 0);
        result.add("rotation", rotation); result.add("rotation_euler", rotation.deepCopy());
        result.add("scale", triple(1, 1, 1));
        result.addProperty("visible", !cutters.contains(name));
        result.addProperty("coordinate_system", coordinateSystem(scene));
        result.addProperty("coordinate_space", "scene_local");
        result.addProperty("block_state_axes", "minecraft_world");
        JsonArray materials = new JsonArray();
        if (object.has("material")) materials.add(object.get("material").getAsString());
        result.add("materials", materials);
        if (!result.has("modifiers")) result.add("modifiers", new JsonArray());
        boolean blender = coordinateSystem(scene).equals("blender_z_up");
        Box bounds = box(object, blender, MachinePlanningBudget.current().maxRadius());
        JsonObject blockBounds = new JsonObject(); blockBounds.add("from", bounds.from().json()); blockBounds.add("to", bounds.to().json());
        result.add("minecraft_block_bounds", blockBounds);
        // 字段沿用 world_bounding_box 这个名字，但八个角仍是场景局部坐标，没有加保存的世界锚点。
        JsonArray corners = new JsonArray();
        for (int x : new int[]{bounds.from().x(), bounds.to().x() + 1})
            for (int y : new int[]{bounds.from().y(), bounds.to().y() + 1})
                for (int z : new int[]{bounds.from().z(), bounds.to().z() + 1})
                    corners.add(blender ? triple(x, -z, y) : triple(x, y, z));
        result.add("world_bounding_box", corners);
        result.addProperty("bounding_box_stage", "transformed_primitive_before_boolean");
        return result;
    }

    private static Set<String> cutters(JsonObject scene) {
        Set<String> result = new LinkedHashSet<>();
        for (var element : scene.getAsJsonArray("objects")) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("role") && object.get("role").getAsString().equals("cutter")) result.add(object.get("name").getAsString());
            if (object.has("modifiers")) for (var modifier : object.getAsJsonArray("modifiers"))
                result.add(modifier.getAsJsonObject().get("object").getAsString());
        }
        return result;
    }
    private static String coordinateSystem(JsonObject scene) {
        return scene.has("coordinate_system") ? scene.get("coordinate_system").getAsString() : "blender_z_up";
    }
    private static JsonArray triple(int x, int y, int z) { JsonArray out = new JsonArray(); out.add(x); out.add(y); out.add(z); return out; }
}
