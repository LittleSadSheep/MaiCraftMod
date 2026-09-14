// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import static org.maiwithu.maicraft.core.blueprint.BuildingSceneGeometry.*;

/** 让模型作者先看到源组件、展开路径及可涂装的面和棱，再进行明确编辑；这些信息不表示世界里已施工。 */
public final class BuildingModelInspection {
    private static final int PAGE = 10;
    private BuildingModelInspection() {}

    public static JsonObject sceneInfo(JsonObject scene, int page) {
        if (page < 0) throw bad("scene page must be nonnegative");
        var model = BuildingModelCompiler.inspection(scene); JsonObject out = base(scene);
        out.addProperty("name", scene.has("name") ? scene.get("name").getAsString() : "Scene");
        out.addProperty("object_count", scene.getAsJsonArray("objects").size()); out.addProperty("expanded_object_count", model.leaves.size());
        out.addProperty("materials_count", scene.getAsJsonObject("materials").size());
        JsonArray objects = new JsonArray(); long start = (long) page * PAGE;
        JsonArray authored = scene.getAsJsonArray("objects");
        for (long i = start; i < Math.min(start + PAGE, authored.size()); i++)
            objects.add(describe(model, authored.get((int) i).getAsJsonObject().get("name").getAsString(), false, 0));
        out.add("objects", objects); out.addProperty("page", page); out.addProperty("page_size", PAGE);
        JsonArray definitions = new JsonArray(); List<String> names = scene.has("components") ? new ArrayList<>(scene.getAsJsonObject("components").keySet()) : List.of();
        for (long i = start; i < Math.min(start + PAGE, names.size()); i++) {
            String name = names.get((int) i); JsonObject definition = new JsonObject(); definition.addProperty("name", name);
            definition.addProperty("object_count", scene.getAsJsonObject("components").getAsJsonObject(name).getAsJsonArray("objects").size()); definitions.add(definition);
        }
        out.add("components", definitions); out.addProperty("component_count", names.size());
        out.addProperty("has_more", start + PAGE < authored.size() || start + PAGE < names.size());
        return out;
    }

    public static JsonObject objectInfo(JsonObject scene, String name) {
        return objectInfo(scene, name, 0);
    }
    public static JsonObject objectInfo(JsonObject scene, String name, int page) {
        return describe(BuildingModelCompiler.inspection(scene), name, true, page);
    }

    public static JsonObject componentInfo(JsonObject scene, String name) {
        return componentInfo(scene, name, 0);
    }
    public static JsonObject componentInfo(JsonObject scene, String name, int page) {
        if (!BuildingModelSchema.applies(scene) || !scene.has("components") || !scene.getAsJsonObject("components").has(name))
            throw bad("Component not found: " + name);
        if (page < 0) throw bad("component page must be nonnegative");
        BuildingModelCompiler.validateWire(scene);
        // 在组件自己的原点做只读展开，仍带原场景材料和组件库；不会创建新版本或移动保存的世界锚点。
        JsonObject probe = scene.deepCopy(), instance = new JsonObject();
        instance.addProperty("name", "component_preview"); instance.addProperty("type", "INSTANCE"); instance.addProperty("component", name);
        instance.add("location", BuildingModelTransform.json(Vec3.ZERO));
        JsonArray objects = new JsonArray(); objects.add(instance); probe.add("objects", objects);
        JsonObject result = base(scene);
        try {
            result = describe(BuildingModelCompiler.inspection(probe), "component_preview", true, page);
            JsonArray localPaths = new JsonArray();
            for (var value : result.getAsJsonArray("expanded_paths")) localPaths.add(value.getAsString().substring("component_preview/".length()));
            result.remove("expanded_paths"); result.add("local_expanded_paths", localPaths);
        } catch (IllegalArgumentException localPlacement) {
            // 组件可由真实实例平移后才对齐格网；局部原点预览失败也必须返回原定义，不能把合法组件说成不存在。
            result.addProperty("local_geometry_unavailable", localPlacement.getMessage());
        }
        result.addProperty("name", name); result.addProperty("coordinate_space", "component_local");
        result.addProperty("local_paths_are_scene_queries", false);
        result.add("definition", scene.getAsJsonObject("components").getAsJsonObject(name).deepCopy());
        return result;
    }

    private static JsonObject describe(BuildingModelExpansion model, String name, boolean detailed, int page) {
        if (page < 0) throw bad("object page must be nonnegative");
        JsonObject source = model.nodes.get(name); List<BuildingModelExpansion.Leaf> members = model.references.get(name);
        if (source == null || members == null) throw bad("Object not found: " + name);
        JsonObject result = source.deepCopy(); result.addProperty("name", name);
        result.addProperty("coordinate_system", coordinateSystem(model.scene)); result.addProperty("coordinate_space", "scene_local");
        result.addProperty("expanded_object_count", members.size());
        result.addProperty("visible", members.stream().anyMatch(leaf -> !model.cutterLeaves.contains(leaf.name())));
        long start = (long) page * 64; JsonArray paths = new JsonArray();
        members.stream().skip(start).limit(64).forEach(leaf -> paths.add(leaf.name()));
        result.add("expanded_paths", paths); result.addProperty("expanded_paths_truncated", start + paths.size() < members.size());
        result.addProperty("page", page); result.addProperty("path_page_size", 64); result.addProperty("has_more", start + paths.size() < members.size());
        result.addProperty("expanded_paths_sample_only", !detailed);
        result.addProperty("editable_source_name", source.get("name").getAsString());
        if (members.isEmpty()) { result.addProperty("empty_after_array_skip", true); return result; }
        Box bounds = bounds(members); JsonObject blockBounds = new JsonObject(); blockBounds.add("from", bounds.from().json()); blockBounds.add("to", bounds.to().json());
        result.add("minecraft_block_bounds", blockBounds); result.addProperty("bounding_box_stage", "expanded_primitives_before_boolean");
        JsonArray corners = new JsonArray(); boolean blender = coordinateSystem(model.scene).equals("blender_z_up");
        for (int x : new int[]{bounds.from().x(), bounds.to().x() + 1}) for (int y : new int[]{bounds.from().y(), bounds.to().y() + 1})
            for (int z : new int[]{bounds.from().z(), bounds.to().z() + 1}) corners.add(BuildingModelTransform.json(blender ? new Vec3(x, -z, y) : new Vec3(x, y, z)));
        result.add("world_bounding_box", corners);
        if (detailed && !source.get("type").getAsString().equals("INSTANCE")) {
            var first = members.getFirst(); result.addProperty("geometry_sample_path", first.name());
            result.addProperty("face_geometry_space", "primitive_local_minecraft_y_up");
            result.addProperty("surface_topology_stage", "primitive_before_boolean");
            result.addProperty("block_state_axes", first.stateAxes());
            JsonArray matrix = new JsonArray(); for (int value : first.transform().axes()) matrix.add(value);
            result.add("minecraft_orientation", matrix); result.add("minecraft_origin", BuildingModelTransform.json(first.transform().point(Vec3.ZERO)));
            JsonArray faces = new JsonArray(), edges = new JsonArray();
            for (var face : first.shape().faces()) {
                JsonObject row = new JsonObject(); row.addProperty("id", face.id()); row.add("normal", BuildingModelTransform.json(face.normal())); row.addProperty("offset", face.offset());
                JsonArray boundary = new JsonArray(); first.shape().edges().stream().filter(edge -> edge.faces().contains(face.id())).forEach(edge -> boundary.add(edge.id())); row.add("edges", boundary); faces.add(row);
            }
            for (var edge : first.shape().edges()) {
                JsonObject row = new JsonObject(); row.addProperty("id", edge.id()); row.add("from", BuildingModelTransform.json(edge.from())); row.add("to", BuildingModelTransform.json(edge.to()));
                JsonArray incident = new JsonArray(); edge.faces().forEach(incident::add); row.add("faces", incident); edges.add(row);
            }
            // 凸多面体原来的 faces 是作者顶点索引，派生表面信息用独立字段，避免读取后无法继续编辑原网格。
            result.add("surface_faces", faces); result.add("surface_edges", edges);
            JsonArray maps = new JsonArray(); first.materialMaps().forEach(map -> maps.add(map.deepCopy())); result.add("material_maps_child_first", maps);
        }
        return result;
    }

    private static Box bounds(List<BuildingModelExpansion.Leaf> leaves) {
        return new Box(new Point(leaves.stream().mapToInt(leaf -> leaf.bounds().from().x()).min().orElseThrow(),
                leaves.stream().mapToInt(leaf -> leaf.bounds().from().y()).min().orElseThrow(), leaves.stream().mapToInt(leaf -> leaf.bounds().from().z()).min().orElseThrow()),
                new Point(leaves.stream().mapToInt(leaf -> leaf.bounds().to().x()).max().orElseThrow(), leaves.stream().mapToInt(leaf -> leaf.bounds().to().y()).max().orElseThrow(), leaves.stream().mapToInt(leaf -> leaf.bounds().to().z()).max().orElseThrow()));
    }
    private static String coordinateSystem(JsonObject scene) { return scene.has("coordinate_system") ? scene.get("coordinate_system").getAsString() : "blender_z_up"; }
    private static JsonObject base(JsonObject scene) {
        JsonObject result = new JsonObject(); result.addProperty("schema_version", 2); result.addProperty("coordinate_system", coordinateSystem(scene)); result.addProperty("coordinate_space", "scene_local"); return result;
    }
}
