// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.build.BuildingBudgets;
import static org.maiwithu.maicraft.core.blueprint.BuildingSceneGeometry.*;

/** 组件展开 -> 图元和空腔采样 -> 面与棱涂装 -> 切割/重叠结算；生成最终蓝图后才交给原生施工。 */
public final class BuildingModelCompiler {
    private record Raster(BuildingModelExpansion.Leaf leaf, BuildingModelPaint paint, List<BuildingModelExpansion.Leaf> cutters) {}
    private record Plan(BuildingModelExpansion model, List<Raster> meshes, long work) {}
    private record Cell(JsonObject state, String owner, boolean voidOnly) {}
    private BuildingModelCompiler() {}

    public static void validateWire(JsonObject scene) { prepare(scene); }
    static BuildingModelExpansion inspection(JsonObject scene) { return prepare(scene).model; }

    private static Plan prepare(JsonObject scene) {
        BuildingModelExpansion model = BuildingModelExpansion.expand(scene);
        List<Raster> meshes = new ArrayList<>(); Map<String, JsonObject> cache = new HashMap<>(); long work = 0;
        for (var leaf : model.leaves) {
            BuildingModelPaint paint = new BuildingModelPaint(model, leaf, cache);
            if (model.cutterLeaves.contains(leaf.name())) continue;
            // 源图元内部判定也逐面比较，必须与空腔、涂装和切割一起计费，不能漏掉大空心模型的第一轮检查。
            var cutters = model.cutters(leaf); long cost = 1L + leaf.shape().faces().size() + paint.sampleCost();
            for (var cutter : cutters) cost += cutter.shape().faces().size();
            try { work = Math.addExact(work, Math.multiplyExact(leaf.bounds().volume(), cost)); }
            catch (ArithmeticException exceeded) { throw bad("model voxel work budget overflow"); }
            // 大厅空气与复杂屋顶仍计算真实采样工作量，但限额由建筑配置提供，独立于导入区域体积。
            if (work > BuildingBudgets.current().maxVoxelWork()) throw bad("model exceeds maxVoxelWork="
                    + BuildingBudgets.current().maxVoxelWork() + " in " + BuildingBudgets.CONFIG_PATH);
            meshes.add(new Raster(leaf, paint, cutters));
        }
        return new Plan(model, List.copyOf(meshes), work);
    }

    public static JsonObject compile(JsonObject scene) {
        Plan plan = prepare(scene); Map<Point, Cell> cells = new LinkedHashMap<>();
        // 整份建筑采用自己的目标预算，不能再被机器规划的较小目标数截断。
        int limit = BuildingBudgets.current().maxTargets();
        JsonObject air = new JsonObject(); air.addProperty("block_id", "minecraft:air"); air.add("properties", new JsonObject());
        boolean strictOverlap = scene.has("overlap_policy") && scene.get("overlap_policy").getAsString().equals("error");
        var conflicts = new HashSet<Point>(); JsonArray examples = new JsonArray(); long overlapEvents = 0;
        for (Raster mesh : plan.meshes) {
            Box bounds = mesh.leaf.bounds();
            for (int y = bounds.from().y(); y <= bounds.to().y(); y++) {
                BlueprintFormats.checkInterrupted();
                for (int z = bounds.from().z(); z <= bounds.to().z(); z++) for (int x = bounds.from().x(); x <= bounds.to().x(); x++) {
                    Point at = new Point(x, y, z); Vec3 center = new Vec3(x + .5, y + .5, z + .5);
                    Vec3 local = mesh.leaf.transform().inverse(center);
                    if (!mesh.leaf.shape().contains(local)) continue;
                    boolean cut = false;
                    for (var cutter : mesh.cutters) if (cutter.contains(center)) { cut = true; break; }
                    JsonObject state = cut ? null : mesh.paint.at(local);
                    if (!cells.containsKey(at) && cells.size() >= limit) throw bad("model exceeds the " + limit
                            + " final block budget; configure maxTargets in " + BuildingBudgets.CONFIG_PATH);
                    // 空心内腔和切割孔只补尚未被别的对象占用的空气；玻璃、框架等独立对象仍可填入。
                    if (state == null) { cells.putIfAbsent(at, new Cell(air, mesh.leaf.name(), true)); continue; }
                    Cell prior = cells.get(at);
                    if (prior != null && !prior.voidOnly && !prior.state.equals(state)) {
                        overlapEvents++; conflicts.add(at);
                        if (strictOverlap) throw bad("different model materials overlap at " + at.json() + ": " + prior.owner + " / " + mesh.leaf.name());
                        if (examples.size() < 16) {
                            JsonObject example = new JsonObject(); example.add("offset", at.json());
                            example.addProperty("previous_object", prior.owner); example.addProperty("incoming_object", mesh.leaf.name());
                            example.add("previous_state", prior.state.deepCopy()); example.add("incoming_state", state.deepCopy()); examples.add(example);
                        }
                    }
                    cells.put(at, new Cell(state, mesh.leaf.name(), false));
                }
            }
        }
        if (cells.isEmpty()) throw bad("model contains no block centres; increase its thickness or correct its placement");
        JsonArray blocks = new JsonArray();
        cells.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparingInt(Point::y).thenComparingInt(Point::z).thenComparingInt(Point::x)))
                .forEach(entry -> { JsonObject cell = entry.getValue().state.deepCopy(); cell.add("offset", entry.getKey().json()); blocks.add(cell); });
        JsonObject metadata = new JsonObject(); metadata.addProperty("compiler", "building_scene_v2");
        metadata.addProperty("coordinate_system", scene.has("coordinate_system") ? scene.get("coordinate_system").getAsString() : "blender_z_up");
        metadata.addProperty("block_state_axes", scene.has("block_state_axes") ? scene.get("block_state_axes").getAsString() : "local");
        metadata.addProperty("authored_object_count", scene.getAsJsonArray("objects").size());
        metadata.addProperty("component_count", scene.has("components") ? scene.getAsJsonObject("components").size() : 0);
        metadata.addProperty("expanded_object_count", plan.model.leaves.size()); metadata.addProperty("cutter_count", plan.model.cutterLeaves.size());
        metadata.addProperty("target_count", blocks.size()); metadata.addProperty("voxelization", "block_center");
        metadata.addProperty("voxel_work", plan.work); metadata.addProperty("overlap_policy", strictOverlap ? "error" : "last_wins");
        metadata.addProperty("overlap_conflicting_cells", conflicts.size()); metadata.addProperty("overlap_events", overlapEvents); metadata.add("overlap_examples", examples);
        JsonObject bounds = new JsonObject();
        bounds.add("from", new Point(cells.keySet().stream().mapToInt(Point::x).min().orElseThrow(), cells.keySet().stream().mapToInt(Point::y).min().orElseThrow(), cells.keySet().stream().mapToInt(Point::z).min().orElseThrow()).json());
        bounds.add("to", new Point(cells.keySet().stream().mapToInt(Point::x).max().orElseThrow(), cells.keySet().stream().mapToInt(Point::y).max().orElseThrow(), cells.keySet().stream().mapToInt(Point::z).max().orElseThrow()).json());
        metadata.add("bounds", bounds);
        JsonObject result = new JsonObject(); result.addProperty("schema_version", 1); result.add("blocks", blocks); result.add("metadata", metadata); return result;
    }
}
