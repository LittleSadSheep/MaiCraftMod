// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.client.preview.PreviewController;
import org.maiwithu.maicraft.client.preview.PreviewSession;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneCompiler;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneStore;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneExport;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneBlocks;
import org.maiwithu.maicraft.core.tools.work.BuildTool;
import org.maiwithu.maicraft.task.TaskResult;

/**
 * 把创建、编辑、查看、导出、预览和施工这些模型操作接到各自实现。只有 operation=build 会返回真正的建筑任务。
 */
final class BuildingSceneAdapter {
    private static final Gson GSON = new Gson();
    private BuildingSceneAdapter() {}

    static IntentAction adapt(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        return adapt(goal, player, runtime, PreviewController::showDesign);
    }

    static IntentAction adapt(Goal goal, LocalPlayer player, IntentRuntime runtime, Predicate<PreviewSession> publish) {
        return adapt(goal, player, runtime, publish, BuildingSceneStore::current);
    }

    static IntentAction adapt(Goal goal, LocalPlayer player, IntentRuntime runtime, Predicate<PreviewSession> publish,
                             Supplier<BuildingSceneStore> stores) {
        BuildingSceneContract.validate(goal);
        JsonObject p = goal.parameters();
        String op = BuildingSceneContract.operation(goal);
        String dimension = player.level().dimension().location().toString();
        BuildingSceneStore.Entry entry = null;
        BuildingSceneStore store = null;
        BuildingSceneStore.Prepared prepared = null;
        Goal.WorldPosition anchor;
        JsonObject blueprint;
        // 已有模型沿用保存时的锚点；调用者若又指定不同地点就报错，不能因角色现在站在别处而移动原设计。
        if (p.has("scene_id")) {
            store = stores.get();
            entry = store.load(p.get("scene_id").getAsString(), dimension);
            anchor = entry.anchor();
            if (goal.target() != null) {
                var requested = SemanticBuildPlanner.investigationAnchor(goal, player, runtime);
                if (!anchor.equals(requested)) throw new IllegalArgumentException("scene_id retains its original anchor; omit target to reuse it");
            }
            if (op.equals("get_scene_info") || op.equals("get_object_info")) {
                Map<String, Object> description = metadata(entry);
                description.putAll(jsonMap(op.equals("get_scene_info")
                        ? org.maiwithu.maicraft.core.blueprint.BuildingSceneInspection.sceneInfo(entry.scene(), p.has("page") ? p.get("page").getAsInt() : 0)
                        : org.maiwithu.maicraft.core.blueprint.BuildingSceneInspection.objectInfo(entry.scene(), p.get("object_name").getAsString())));
                return new IntentAction.Report(TaskResult.ok("Building model inspected; no construction was started", description), null);
            }
            JsonObject source = op.equals("update_scene")
                    ? BuildingSceneStore.applyPatch(entry.scene(), p.getAsJsonObject("edits")) : entry.scene();
            if (op.equals("update_scene")) {
                prepared = store.prepare(source);
                blueprint = prepared.blueprint();
            } else blueprint = BuildingSceneCompiler.compile(source);
        } else {
            anchor = SemanticBuildPlanner.investigationAnchor(goal, player, runtime);
            if (anchor == null) throw new IllegalArgumentException("A new model needs an exact current_place, coordinates or remembered landmark target");
            anchor = new Goal.WorldPosition(anchor.x(), anchor.y(), anchor.z(), dimension);
            if (p.has("scene")) {
                store = stores.get();
                prepared = store.prepare(p.getAsJsonObject("scene"));
                blueprint = prepared.blueprint();
            } else blueprint = p.getAsJsonObject("blueprint");
        }
        // 除查看信息外，其他操作先走相同的施工参数与材料检查，再决定保存、预览、导出还是施工。
        JsonObject args = buildArguments(blueprint, anchor, p);
        // 先确认注册名和状态能被当前游戏表达，再保存新版本；缺材料种类不会被换成近似方块。
        var targets = BuildTool.resolvedTargets(args.getAsJsonArray("ops"), true);
        if (op.equals("update_scene")) entry = store.updatePrepared(entry.sceneId(), dimension, prepared);
        if (entry == null && prepared != null) entry = store.savePrepared(prepared, anchor);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("construction_started", false);
        data.put("block_count", targets.size());
        data.put("anchor", Map.of("x", anchor.x(), "y", anchor.y(), "z", anchor.z(), "dimension", dimension));
        if (entry != null) data.putAll(metadata(entry));
        if (op.equals("build")) {
            JsonObject facts = new JsonObject();
            facts.addProperty("design_source", "llm_authored_model");
            if (entry != null) facts.addProperty("scene_id", entry.sceneId());
            args.add("semantic_contract", facts);
            return new IntentAction.Tool("build", args.toString());
        }
        // 预览要求所有格子已加载且处于建造高度内；它不会为了显示远处模型主动导航或加载区块。
        if (op.equals("preview")) {
            Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
            targets.forEach(target -> {
                if (!player.level().hasChunkAt(target.pos()) || player.level().isOutsideBuildHeight(target.pos()))
                    throw new IllegalArgumentException("Preview requires all model cells within loaded buildable terrain");
                cells.put(target.pos(), target.desiredState());
            });
            var session = PreviewSession.design("scene-" + java.util.UUID.randomUUID(), dimension, goal.outcome(), cells);
            if (!publish.test(session)) return new IntentAction.Report(TaskResult.fail("The blueprint preview could not be displayed", data), null);
            data.put("preview_created", true);
            data.put("preview_id", session.owner());
        } else if (op.equals("export_scene")) {
            String format = p.has("format") ? p.get("format").getAsString() : "json";
            var exported = BuildingSceneExport.write(entry.sceneId(), blueprint, format);
            data.put("file", exported.toString());
            data.put("format", format);
            data.put("minecraft_offset", BuildingSceneExport.minimum(blueprint));
        } else {
            data.putAll(jsonMap(org.maiwithu.maicraft.core.blueprint.BuildingSceneInspection.sceneInfo(entry.scene(), 0)));
        }
        return new IntentAction.Report(TaskResult.ok("Building model " + op + " completed; no construction was started", data), null);
    }

    // 把局部偏移加到固定锚点，生成逐格 set；拒绝实体、部件和非空 NBT 配置，这些另由机器能力处理。
    static JsonObject buildArguments(JsonObject blueprint, Goal.WorldPosition anchor, JsonObject parameters) {
        org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocument.validateWire(blueprint);
        if (blueprint.has("entities") && !blueprint.getAsJsonArray("entities").isEmpty())
            throw new IllegalArgumentException("Building models do not support entity installation");
        JsonArray ops = new JsonArray();
        for (var value : blueprint.getAsJsonArray("blocks")) {
            JsonObject cell = value.getAsJsonObject();
            if (cell.has("part") || cell.has("nbt") && !cell.getAsJsonObject("nbt").isEmpty())
                throw new IllegalArgumentException("Building models require ordinary blocks; native parts and NBT configuration need machine abilities");
            String id = cell.get("block_id").getAsString();
            BuildingSceneBlocks.resolve(cell);
            JsonObject properties = cell.has("properties") ? cell.getAsJsonObject("properties") : new JsonObject();
            JsonObject op = new JsonObject();
            op.addProperty("op", "set"); op.addProperty("block_id", id);
            var offset = cell.getAsJsonArray("offset");
            op.addProperty("x", Math.addExact(anchor.x(), offset.get(0).getAsInt()));
            op.addProperty("y", Math.addExact(anchor.y(), offset.get(1).getAsInt()));
            op.addProperty("z", Math.addExact(anchor.z(), offset.get(2).getAsInt()));
            op.add("properties", properties.deepCopy()); ops.add(op);
        }
        if (ops.size() > org.maiwithu.maicraft.core.build.BuildShapes.MAX_TOTAL_CELLS)
            throw new IllegalArgumentException("Building model exceeds the bounded construction cell budget");
        JsonObject args = new JsonObject(); args.add("ops", ops);
        // 模型保持明确材料与精确状态；允许分批备料，默认不替换已有方块。specified 只固定材质，实际取材按 ordinary 策略执行。
        args.addProperty("exact_states", true);
        args.addProperty("replace_existing", parameters.has("replace_existing") && parameters.get("replace_existing").getAsBoolean());
        args.addProperty("allow_partial", true);
        args.addProperty("broaden_material_families", false);
        String policy = parameters.has("material_policy") ? parameters.get("material_policy").getAsString() : "specified";
        args.addProperty("material_policy", policy.equals("specified") ? "ordinary" : policy);
        if (parameters.has("protected_labels")) args.add("protected_labels", parameters.get("protected_labels").deepCopy());
        return args;
    }

    // 返回可再次引用的模型编号、资源地址、父版本和锚点；construction_started=false 不代表模型已经施工。
    private static Map<String, Object> metadata(BuildingSceneStore.Entry entry) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("construction_started", false);
        result.put("scene_id", entry.sceneId());
        result.put("scene_uri", org.maiwithu.maicraft.mcp.knowledge.BuildingSceneResources.sceneUri(entry.sceneId()));
        result.put("blueprint_uri", org.maiwithu.maicraft.mcp.knowledge.BuildingSceneResources.blueprintUri(entry.sceneId()));
        if (entry.parentSceneId() != null) result.put("parent_scene_id", entry.parentSceneId());
        var anchor = entry.anchor();
        result.put("anchor", Map.of("x", anchor.x(), "y", anchor.y(), "z", anchor.z(), "dimension", anchor.dimension()));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonMap(JsonObject object) { return GSON.fromJson(object, LinkedHashMap.class); }
}
