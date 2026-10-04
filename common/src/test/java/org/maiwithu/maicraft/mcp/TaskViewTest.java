package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.client.preview.PreviewController;
import org.maiwithu.maicraft.client.preview.PreviewSession;
import org.maiwithu.maicraft.core.task.build.BuildPreviewGate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** 大蓝图与重复失败不应撑满轮询；失去旧上下文后仍能找回每一格输入和消费限制。 */
public final class TaskViewTest {
    public static void main(String[] args) throws Exception {
        JsonObject parameters = new JsonObject(); JsonArray blocks = new JsonArray();
        for (int i = 0; i < 800; i++) {
            JsonObject block = new JsonObject(); block.addProperty("block_id", "minecraft:stone");
            JsonArray offset = new JsonArray(); offset.add(i); offset.add(0); offset.add(0);
            block.add("offset", offset); blocks.add(block);
        }
        parameters.add("blocks", blocks);
        Goal goal = new Goal("maicraft:build_machine", "搭建石质围墙", null, parameters.toString(), "{}", List.of(), List.of());
        IntentTaskRecord task = new IntentTaskRecord(UUID.randomUUID(), UUID.randomUUID(), goal);
        completedReplacementKeepsItsExecutedAbility();
        task.setState(TaskState.RUNNING);
        TaskResult failure = TaskResult.fail("物料转移未确认", Map.of("outcome_uncertain", true,
                "mechanical_retry_allowed", false, "pending_output", Map.of("item_id", "minecraft:stone", "count", 3)));
        var add = IntentTaskRecord.class.getDeclaredMethod("addAttempt", IntentTaskRecord.AttemptSnapshot.class); add.setAccessible(true);
        for (int i = 0; i < 64; i++) add.invoke(task, new IntentTaskRecord.AttemptSnapshot(0, goal, TaskState.FAILED, failure.message(), failure.toJson(), i));
        JsonObject context = new JsonObject(); context.add("goal", goal.toJson());
        context.addProperty("ordinary_retry_allowed", false); context.add("failure", JsonParser.parseString(failure.toJson()));
        var decision = new IntentTaskRecord.DecisionSnapshot(UUID.randomUUID(), "核对已发生的物料转移，再决定如何继续",
                List.of(new IntentTaskRecord.DecisionOption("cancel", "停止后续施工")), context.toString());
        var decide = IntentTaskRecord.class.getDeclaredMethod("requestDecision", IntentTaskRecord.DecisionSnapshot.class, long.class);
        decide.setAccessible(true); decide.invoke(task, decision, 90L);
        JsonObject compact = TaskView.status(task), full = MaiCraftRuntimeFacade.taskSnapshot(task);
        // 当前决策与可恢复历史分别按字段核对，不用固定字数要求隐藏未知物料效果。
        check(!compact.has("goal") && !compact.has("attempts") && compact.get("retained_attempt_count").getAsInt() == 64, "retained history is counted instead of replayed");
        var compactContext = compact.getAsJsonObject("decision").getAsJsonObject("context");
        check(!compactContext.get("ordinary_retry_allowed").getAsBoolean()
                && compactContext.getAsJsonObject("failure").getAsJsonObject("data").get("outcome_uncertain").getAsBoolean(), "unsafe consumption is visible before answering");
        // decision 自带可照抄的应答样板；样本文身必须直接通过 task 工具的 schema 校验。
        var example = compact.getAsJsonObject("decision").getAsJsonObject("answer_example");
        check(example.get("action").getAsString().equals("answer")
                && example.get("task_id").getAsString().equals(task.externalId().toString())
                && example.getAsJsonObject("answer").get("decision_id").getAsString().equals(decision.id().toString())
                && example.getAsJsonObject("answer").get("choice").getAsString().equals("cancel"),
                "decision ships a copyable answer example");
        PublicToolCatalog.validateAndNormalize("task", example.deepCopy());
        JsonObject request = new JsonObject(); request.addProperty("action", "get");
        request.addProperty("task_id", task.externalId().toString()); request.addProperty("path", "/goal/parameters/blocks");
        request.addProperty("limit", 20); JsonArray recovered = new JsonArray();
        // 首页一次交付冻结的全部施工输入；显式偏移续读仍指向同一份设计，不产生游戏动作。
        request.addProperty("offset", 0);
        var whole = TaskView.read(task, PublicToolCatalog.validateAndNormalize("task", request)).getAsJsonObject("detail");
        check(!whole.has("items") && whole.get("value").equals(blocks),
                "first detail page delivers the frozen whole array");
        recovered.addAll(whole.getAsJsonArray("value"));
        request.addProperty("offset", 5);
        var page = TaskView.read(task, PublicToolCatalog.validateAndNormalize("task", request)).getAsJsonObject("detail");
        check(page.getAsJsonArray("items").get(0).getAsJsonObject().get("value").getAsJsonObject().equals(blocks.get(5))
                && page.has("next_offset"), "paged detail reads slice the same array");
        check(recovered.equals(blocks) && full.equals(MaiCraftRuntimeFacade.taskSnapshot(task)), "detail pages recover unchanged inputs without game actions");
        List<IntentTaskRecord> retained = new ArrayList<>();
        for (int i = 0; i < 20; i++) retained.add(new IntentTaskRecord(UUID.randomUUID(), null, goal));
        var listed = TaskView.list(retained, 5, 5);
        // 任务列表按约定条数和游标返回；每项需要交付的事实不受额外文字长度断言限制。
        check(listed.getAsJsonArray("tasks").size() == 5 && listed.get("next_offset").getAsInt() == 10,
                "list returns five summaries and continuation");
        request.addProperty("action", "cancel");
        try { PublicToolCatalog.validateAndNormalize("task", request); throw new AssertionError("control accepted read path"); }
        catch (IllegalArgumentException expected) { /* 读取参数不能意外变成一条控制请求。 */ }
        frozenTickStillReportsPreview(goal);
        materialHandoffStaysVisible(blocks);
        latestSnapshotStaysVisible(goal, blocks);
        failureEffectsStayVisible();
        System.out.println("TaskViewTest: full=" + full.toString().length() + " chars; status=" + compact.toString().length() + " chars; passed");
    }

    private static void failureEffectsStayVisible() throws Exception {
        // 前一步已消耗原料，后一步失败时不能只显示“完成了一步”；完整效果和资料入口须一起到达模型。
        var raw = JsonParser.parseString("""
                {"success":false,"message":"后续步骤未完成","data":{
                  "completed_effects":[{"step_index":0,"confirmed_effect":{"success":true,
                    "data":{"consumed":{"minecraft:iron_ingot":3},"created":{"minecraft:bucket":1}}}}],
                  "remaining_effects":[{"step_index":1,"state":"failed_current"}],
                  "outcome_uncertain":true,"mechanical_retry_allowed":false,
                  "recovery_options":[{"id":"inspect_related_knowledge","knowledge":[{
                    "read_arguments":{"view":"knowledge","resource_uri":"maicraft://knowledge/recipes/minecraft/bucket"}}]}]}}
                """).getAsJsonObject();
        var method = TaskView.class.getDeclaredMethod("result", JsonObject.class, String.class); method.setAccessible(true);
        var shown = (JsonObject) method.invoke(null, raw, "/terminal/result");
        JsonObject data = shown.getAsJsonObject("data"), source = raw.getAsJsonObject("data");
        check(data.get("completed_effects").equals(source.get("completed_effects"))
                && data.get("remaining_effects").equals(source.get("remaining_effects")), "default failure shows confirmed and unfinished effects");
        var options = data.getAsJsonArray("recovery_options");
        PublicToolCatalog.validateAndNormalize("perceive", options.get(0).getAsJsonObject().getAsJsonArray("knowledge")
                .get(0).getAsJsonObject().getAsJsonObject("read_arguments"));
        check(data.get("outcome_uncertain").getAsBoolean() && !data.get("mechanical_retry_allowed").getAsBoolean(),
                "showing effects and knowledge does not permit another uncertain consumption");
    }

    // 旧设计和效果账本很大时，首份失败查询仍直接显示新场地编号、目标和可检查的实际方块。
    private static void latestSnapshotStaysVisible(Goal goal, JsonArray blocks) throws Exception {
        JsonObject latest = JsonParser.parseString("""
                {"snapshot_id":"current-site","target":{"kind":"landmark","label":"platform"},
                 "structure_complete":true,"palette":[{"block_id":"minecraft:gold_block","properties":{}}],
                 "relative_blocks":[[0,-1,0,0]]}
                """).getAsJsonObject();
        latest.addProperty("large_native_evidence", "observed".repeat(1500));
        // 完整体积很大时仍直接显示连续行；其调色板单独保留，不能与三维快照的索引混用。
        latest.add("site_geometry", JsonParser.parseString("""
                {"structure_complete":true,"palette":[{"block_id":"minecraft:gold_block","properties":{}}],
                 "surface_and_obstacles":[[-1,0,0,799,0]],"geometry_format":"inclusive horizontal runs"}
                """));
        TaskResult failure = TaskResult.fail("machine_snapshot_changed", Map.of("failure_code", "machine_snapshot_changed",
                "previous_snapshot_id", "old-site", "latest_snapshot", latest, "machine_layout", blocks));
        var task = new IntentTaskRecord(UUID.randomUUID(), null, goal);
        var finish = IntentTaskRecord.class.getDeclaredMethod("terminal", TaskState.class, TaskResult.class, long.class);
        finish.setAccessible(true); finish.invoke(task,TaskState.FAILED,failure,99L);
        JsonObject shown = TaskView.status(task).getAsJsonObject("terminal").getAsJsonObject("result");
        JsonObject snapshot = shown.getAsJsonObject("latest_snapshot");
        check(shown.get("failure_code").getAsString().equals("machine_snapshot_changed")
                        && snapshot.get("snapshot_id").getAsString().equals("current-site")
                        && snapshot.getAsJsonObject("target").get("label").getAsString().equals("platform")
                        && snapshot.getAsJsonArray("relative_blocks").get(0).toString().equals("[0,-1,0,0]")
                        && snapshot.getAsJsonObject("site_geometry").getAsJsonArray("surface_and_obstacles").get(0).toString().equals("[-1,0,0,799,0]"),
                "default failure presentation preserves the usable new snapshot beside large diagnostics");
        // 展示层不再外链详情路径；完整原件固定保留在终态结果的同一数据路径下。
        var exact = JsonReadback.resolve(MaiCraftRuntimeFacade.taskSnapshot(task), "/terminal/result/data/latest_snapshot");
        check(exact.equals(latest) && !latest.has("omitted"), "current observation details point to the unchanged retained task result");
        // 默认失败回执也完整带出当前现场，模型不用跟随历史详情链接才能做下一步决策。
        check(snapshot.equals(latest), "current observation is fully available in the first receipt");
    }

    private static void materialHandoffStaysVisible(JsonArray blocks) throws Exception {
        // 即使已有大蓝图和批次诊断，首次查询失败任务也能看见原料加工链接，而不是再次执行来探原因。
        var raw = JsonParser.parseString(TaskResult.fail("supply failed", Map.of("planning_handoff",
                Map.of("knowledge_uris", List.of("maicraft://knowledge/recipes/create/polished_rose_quartz"),
                        "blocked_need", Map.of("item_ids", List.of("create:polished_rose_quartz"), "missing", 3,
                                "required_final_count", 3, "observed_final_count", 0),
                        "recipe_trace", "history".repeat(1000), "ordinary_crafting_scope", "inventory/crafting-table grids"))).toJson()).getAsJsonObject();
        raw.getAsJsonObject("data").add("machine_layout", blocks);
        raw.getAsJsonObject("data").addProperty("body_preparation_required", true);
        raw.getAsJsonObject("data").add("food_preparation", JsonParser.parseString("{\"food\":10,\"health\":7}"));
        var method = TaskView.class.getDeclaredMethod("result", JsonObject.class, String.class); method.setAccessible(true);
        var displayed = (JsonObject) method.invoke(null, raw, "/terminal/result");
        check(displayed.get("material_planning_required").getAsBoolean()
                && displayed.getAsJsonObject("planning_handoff").getAsJsonArray("knowledge_uris").size() == 1,
                "material planning facts survive default task compaction");
        // 交接事实完整交付：不再折叠成摘要引用，超长轨迹也原样在场，调用方不必翻页就知道缺什么。
        var summary = displayed.getAsJsonObject("planning_handoff"); var need = summary.getAsJsonObject("blocked_need");
        check(!summary.has("summary_only") && need.get("missing").getAsInt() == 3
                && need.getAsJsonArray("item_ids").get(0).getAsString().equals("create:polished_rose_quartz")
                && summary.get("recipe_trace").getAsString().equals("history".repeat(1000)),
                "large handoff keeps the full trace and shortage inline without a summary detour");
        check(summary.get("ordinary_crafting_scope").getAsString().contains("crafting-table grids"),
                "ordinary crafting scope stays visible even beside a large recipe trace");
        check(displayed.get("body_preparation_required").getAsBoolean()
                && displayed.getAsJsonObject("food_preparation").get("food").getAsInt() == 10,
                "body requirements remain visible beside large material evidence");
    }

    private static void frozenTickStillReportsPreview(Goal goal) throws Exception {
        // 调度冻结后不再调用 observeExecution，仍通过真实 TaskView 查询审核状态，覆盖只测进度辅助方法漏掉的停刻分支。
        var task = new IntentTaskRecord(UUID.randomUUID(), null, goal);
        task.setState(TaskState.RUNNING);
        var cached = IntentTaskRecord.class.getDeclaredField("activeExecution"); cached.setAccessible(true);
        cached.set(task, JsonParser.parseString("{\"phase\":\"survey\",\"observed_game_time\":10}").getAsJsonObject());
        var current = PreviewController.class.getDeclaredField("current"); current.setAccessible(true);
        var owner = BuildPreviewGate.class.getDeclaredField("reviewOwner"); owner.setAccessible(true);
        var root = BuildPreviewGate.class.getDeclaredField("reviewRoot"); root.setAccessible(true);
        Object oldCurrent = current.get(null), oldOwner = owner.get(null), oldRoot = root.get(null);
        try {
            var review = new PreviewSession(task.publicId(), "minecraft:overworld", "review", Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState()));
            current.set(null, review); owner.set(null, task); root.set(null, task);
            var state = TaskView.status(task).getAsJsonObject("active_execution");
            check(state.get("phase").getAsString().equals("waiting_for_blueprint_confirmation")
                    && state.get("requires_player_confirmation").getAsBoolean(), "停刻期间查询仍报告人工审核等待");
            review.confirm();
            check(!TaskView.status(task).getAsJsonObject("active_execution").has("requires_player_confirmation"),
                    "玩家确认后不遗留过期审核标记");
        } finally {
            current.set(null, oldCurrent); owner.set(null, oldOwner); root.set(null, oldRoot);
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void completedReplacementKeepsItsExecutedAbility() throws Exception {
        // 单独读取完成状态时也要看见实际观察能力；原始生产请求只保留为追溯标签。
        Goal requested = new Goal("maicraft:operate_machine", "产出精密构件", null, "{}", "{}", List.of(), List.of());
        Goal inspection = new Goal("maicraft:inspect_machine", "检查机器", null, "{}", "{}", List.of(), List.of());
        var record = new IntentTaskRecord(UUID.randomUUID(), null, requested);
        var replace = IntentTaskRecord.class.getDeclaredMethod("replaceCurrent", Goal.class); replace.setAccessible(true); replace.invoke(record, inspection);
        var add = IntentTaskRecord.class.getDeclaredMethod("addStepResult", IntentTaskRecord.StepSnapshot.class); add.setAccessible(true);
        add.invoke(record, new IntentTaskRecord.StepSnapshot(0, inspection.ability(), true, "observed", TaskResult.ok("observed").toJson()));
        record.setState(TaskState.SUCCESS);
        JsonObject status = TaskView.status(record);
        check(status.get("ability").getAsString().equals(inspection.ability())
                && status.get("outcome").getAsString().equals(requested.outcome())
                && status.get("outcome_scope").getAsString().equals("requested_intent"), "terminal ability is actual work while original outcome is explicitly intent");
        check(status.get("all_steps_scope").getAsString().contains("replacement"), "current step success does not certify an abandoned production request");
    }
}
