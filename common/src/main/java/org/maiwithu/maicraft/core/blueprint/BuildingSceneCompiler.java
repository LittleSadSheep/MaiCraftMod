// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.maiwithu.maicraft.core.build.BuildShapes;
import org.maiwithu.maicraft.core.integration.machine.MachinePlanningBudget;
import static org.maiwithu.maicraft.core.blueprint.BuildingSceneGeometry.*;

/**
 * 把命名的盒子和开孔关系变成逐格蓝图。例如先定义墙，再用另一个盒子从这面墙里扣出窗洞。
 */
public final class BuildingSceneCompiler {
    private static final Set<String> FIELDS = Set.of("schema_version", "name", "coordinate_system", "materials", "objects");
    private static final Set<String> OBJECT_FIELDS = Set.of("name", "type", "primitive", "role", "location", "dimensions", "rotation_euler", "material", "modifiers");
    private record Mesh(String name, Box box, String material, boolean cutter, List<String> cuts) {}
    private record Model(Map<String, JsonObject> materials, Map<String, Mesh> meshes, Set<String> cutters, String coordinates, int limit) {}
    private BuildingSceneCompiler() {}

    /**
     * 只检查对象、材料、引用和工作量范围，不把每个盒子展开成方块。
     */
    public static void validateWire(JsonObject scene) { parse(scene); }

    /**
     * 默认使用 Blender 的 z 向上坐标，转成游戏坐标 [x,z,-y]；也可明确使用游戏的 y 向上坐标。
     * 后写的普通对象覆盖前面的对象；开孔只扣掉引用它的那一个对象，不挖掉其他对象填进来的玻璃等材料。
     * 孔内没有其他对象时保存为空气目标，未被任何对象涉及的位置则不进入施工单。材料朝向属性仍按游戏世界轴解释。
     */
    public static JsonObject compile(JsonObject scene) {
        Model model = parse(scene);
        Map<String, JsonObject> materials = model.materials;
        Map<String, Mesh> meshes = model.meshes;
        Set<String> cutters = model.cutters;
        int limit = model.limit;
        Map<Point, JsonObject> cells = new LinkedHashMap<>();
        JsonObject air = new JsonObject(); air.addProperty("block_id", "minecraft:air"); air.add("properties", new JsonObject());
        for (Mesh mesh : meshes.values()) {
            // 显式 role=cutter 或被任何开孔修改器引用的对象，都只用于扣洞，不单独铺成实体。
            if (cutters.contains(mesh.name)) continue;
            List<Box> cuts = mesh.cuts.stream().map(name -> meshes.get(name).box).toList();
            Box box = mesh.box;
            for (int y = box.from().y(); y <= box.to().y(); y++) {
                BlueprintFormats.checkInterrupted();
                for (int z = box.from().z(); z <= box.to().z(); z++) for (int x = box.from().x(); x <= box.to().x(); x++) {
                    Point at = new Point(x, y, z);
                    if (!cells.containsKey(at) && cells.size() >= limit) throw bad("scene exceeds the " + limit + " final block budget");
                    boolean removed = false;
                    for (Box cut : cuts) if (cut.contains(at)) { removed = true; break; }
                    // 被扣掉的位置仅在还没有其他对象占用时补空气；正常实体则可以覆盖之前的空气或实体。
                    if (removed) cells.putIfAbsent(at, air);
                    else cells.put(at, materials.get(mesh.material));
                }
            }
        }
        JsonObject result = new JsonObject(); result.addProperty("schema_version", 1);
        JsonArray blocks = new JsonArray();
        cells.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparingInt(Point::y).thenComparingInt(Point::z).thenComparingInt(Point::x)))
                .forEach(entry -> { JsonObject cell = entry.getValue().deepCopy(); cell.add("offset", entry.getKey().json()); blocks.add(cell); });
        result.add("blocks", blocks);
        JsonObject metadata = new JsonObject(); metadata.addProperty("compiler", "building_scene_v1");
        metadata.addProperty("coordinate_system", model.coordinates); metadata.addProperty("block_state_axes", "minecraft_world");
        metadata.addProperty("object_count", meshes.size()); metadata.addProperty("cutter_count", cutters.size());
        metadata.addProperty("target_count", cells.size()); metadata.addProperty("overlap_rule", "later_solid_wins; cuts_only_affect_their_target_mesh");
        JsonObject bounds = new JsonObject();
        bounds.add("from", new Point(cells.keySet().stream().mapToInt(Point::x).min().orElseThrow(), cells.keySet().stream().mapToInt(Point::y).min().orElseThrow(), cells.keySet().stream().mapToInt(Point::z).min().orElseThrow()).json());
        bounds.add("to", new Point(cells.keySet().stream().mapToInt(Point::x).max().orElseThrow(), cells.keySet().stream().mapToInt(Point::y).max().orElseThrow(), cells.keySet().stream().mapToInt(Point::z).max().orElseThrow()).json());
        metadata.add("bounds", bounds); result.add("metadata", metadata);
        return result;
    }

    private static Model parse(JsonObject scene) {
        if (scene == null) throw bad("scene must be an object");
        keys(scene, FIELDS, "scene");
        if (scene.has("name")) string(scene.get("name"), 128, "scene.name");
        if (scene.has("schema_version")) integer(scene.get("schema_version"), 1, 1, "schema_version");
        String coordinates = scene.has("coordinate_system") ? string(scene.get("coordinate_system"), 32, "coordinate_system") : "blender_z_up";
        if (!coordinates.equals("blender_z_up") && !coordinates.equals("minecraft_y_up")) throw bad("unsupported coordinate_system: " + coordinates);
        MachinePlanningBudget budget = MachinePlanningBudget.current();
        int limit = Math.min(BuildShapes.MAX_TOTAL_CELLS, budget.maxTargets());
        Map<String, JsonObject> materials = materials(object(scene.get("materials"), "materials"), budget.maxComponents());
        Map<String, Mesh> meshes = new LinkedHashMap<>();
        Set<String> cutters = new HashSet<>();
        long modifierCount = 0;
        for (JsonElement entry : array(scene.get("objects"), 1, budget.maxComponents(), "objects")) {
            JsonObject object = object(entry, "scene object"); keys(object, OBJECT_FIELDS, "scene object");
            String name = string(object.get("name"), 64, "object.name");
            boolean cutter = false;
            if (object.has("role")) {
                String role = string(object.get("role"), 16, "role");
                if (!role.equals("cutter") && !role.equals("solid")) throw bad("role must be solid or cutter");
                cutter = role.equals("cutter");
            }
            String material = object.has("material") ? string(object.get("material"), 64, "material") : null;
            if (material != null && !materials.containsKey(material)) throw bad("unknown material: " + material);
            List<String> cuts = new ArrayList<>();
            if (object.has("modifiers")) for (JsonElement modifier : array(object.get("modifiers"), 0, budget.maxConnections(), "modifiers")) {
                if (++modifierCount > budget.maxConnections()) throw bad("scene exceeds the modifier budget");
                JsonObject cut = object(modifier, "modifier"); keys(cut, Set.of("type", "operation", "object"), "modifier");
                if (!string(cut.get("type"), 16, "modifier.type").equals("BOOLEAN")
                        || !string(cut.get("operation"), 16, "modifier.operation").equals("DIFFERENCE"))
                    throw bad("only BOOLEAN DIFFERENCE modifiers are supported");
                String target = string(cut.get("object"), 64, "modifier.object");
                if (target.equals(name) || cuts.contains(target)) throw bad("self-referencing or duplicate Boolean cutter: " + target);
                cuts.add(target); cutters.add(target);
            }
            Mesh mesh = new Mesh(name, box(object, coordinates.equals("blender_z_up"), budget.maxRadius()), material, cutter, List.copyOf(cuts));
            if (meshes.putIfAbsent(name, mesh) != null) throw bad("duplicate scene object: " + name);
            if (cutter) cutters.add(name);
        }
        for (String name : cutters) {
            Mesh cutter = meshes.get(name);
            if (cutter == null) throw bad("Boolean cutter object not found: " + name);
            if (!cutter.cuts.isEmpty()) throw bad("Boolean cutters must be primitive meshes without modifiers: " + name);
        }
        // 展开前分别限制单对象体积和总比较次数；重叠对象也计入工作量，不能用最后去重后的格数代替。
        long work = 0;
        int solidCount = 0;
        for (Mesh mesh : meshes.values()) if (!cutters.contains(mesh.name)) {
            solidCount++;
            if (mesh.material == null) throw bad("solid object requires a material: " + mesh.name);
            long volume = mesh.box.volume(), multiplier = Math.max(1, mesh.cuts.size());
            if (volume > limit) throw bad("mesh exceeds the " + limit + " final block budget: " + mesh.name);
            if (volume > BlueprintFormats.MAX_REGION_VOLUME / multiplier
                    || (work += volume * multiplier) > BlueprintFormats.MAX_REGION_VOLUME)
                throw bad("scene exceeds the voxelization work budget");
        }
        if (solidCount == 0) throw bad("scene has no solid objects to compile");
        return new Model(materials, meshes, cutters, coordinates, limit);
    }

    // 这里仅整理材料名字与状态文本，不查游戏注册表；保存或展示前的实际材料验证由适配层继续完成。
    private static Map<String, JsonObject> materials(JsonObject source, int limit) {
        if (source.isEmpty() || source.size() > limit) throw bad("materials must contain 1.." + limit + " named states");
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (var entry : source.entrySet()) {
            if (entry.getKey().isBlank() || entry.getKey().length() > 64) throw bad("invalid material name");
            JsonObject state = object(entry.getValue(), "material"); keys(state, Set.of("block_id", "properties"), "material");
            String id = string(state.get("block_id"), 256, "block_id");
            if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw bad("block_id requires an exact namespaced registry ID");
            JsonObject properties = state.has("properties") ? object(state.get("properties"), "properties") : new JsonObject();
            if (properties.size() > 32) throw bad("too many material block state properties");
            for (var property : properties.entrySet()) {
                if (property.getKey().isBlank() || property.getKey().length() > 64) throw bad("invalid material property name");
                string(property.getValue(), 128, "property value");
            }
            JsonObject normalized = new JsonObject(); normalized.addProperty("block_id", id); normalized.add("properties", properties.deepCopy());
            result.put(entry.getKey(), normalized);
        }
        return result;
    }
}
