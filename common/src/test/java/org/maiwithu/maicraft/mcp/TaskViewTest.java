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
        check(compact.toString().length() < 3500 && full.toString().length() > 70000, "large task status remains small");
        check(!compact.has("goal") && !compact.has("attempts") && compact.get("retained_attempt_count").getAsInt() == 64, "retained history is counted instead of replayed");
        var compactContext = compact.getAsJsonObject("decision").getAsJsonObject("context");
        check(!compactContext.get("ordinary_retry_allowed").getAsBoolean()
                && compactContext.getAsJsonObject("failure").getAsJsonObject("data").get("outcome_uncertain").getAsBoolean(), "unsafe consumption is visible before answering");
        JsonObject request = new JsonObject(); request.addProperty("action", "get");
        request.addProperty("task_id", task.externalId().toString()); request.addProperty("path", "/goal/parameters/blocks");
        request.addProperty("limit", 20); int offset = 0; JsonArray recovered = new JsonArray();
        while (true) {
            request.addProperty("offset", offset);
            var page = TaskView.read(task, PublicToolCatalog.validateAndNormalize("task", request)).getAsJsonObject("detail");
            page.getAsJsonArray("items").forEach(row -> recovered.add(row.getAsJsonObject().get("value")));
            if (!page.has("next_offset")) break;
            offset = page.get("next_offset").getAsInt();
        }
        check(recovered.equals(blocks) && full.equals(MaiCraftRuntimeFacade.taskSnapshot(task)), "detail pages recover unchanged inputs without game actions");
        List<IntentTaskRecord> retained = new ArrayList<>();
        for (int i = 0; i < 20; i++) retained.add(new IntentTaskRecord(UUID.randomUUID(), null, goal));
        var listed = TaskView.list(retained, 5, 5);
        check(listed.getAsJsonArray("tasks").size() == 5 && listed.get("next_offset").getAsInt() == 10
                && listed.toString().length() < 4000, "list returns five summaries and continuation");
        request.addProperty("action", "cancel");
        try { PublicToolCatalog.validateAndNormalize("task", request); throw new AssertionError("control accepted read path"); }
        catch (IllegalArgumentException expected) { /* 读取参数不能意外变成一条控制请求。 */ }
        frozenTickStillReportsPreview(goal);
        materialHandoffStaysVisible(blocks);
        System.out.println("TaskViewTest: full=" + full.toString().length() + " chars; status=" + compact.toString().length() + " chars; passed");
    }

    private static void materialHandoffStaysVisible(JsonArray blocks) throws Exception {
        // 即使已有大蓝图和批次诊断，首次查询失败任务也能看见原料加工链接，而不是再次执行来探原因。
        var raw = JsonParser.parseString(TaskResult.fail("supply failed", Map.of("planning_handoff",
                Map.of("knowledge_uris", List.of("maicraft://knowledge/recipes/create/polished_rose_quartz"),
                        "blocked_need", Map.of("item_ids", List.of("create:polished_rose_quartz"), "missing", 3,
                                "required_final_count", 3, "observed_final_count", 0),
                        "recipe_trace", "history".repeat(1000)))).toJson()).getAsJsonObject();
        raw.getAsJsonObject("data").add("machine_layout", blocks);
        var method = TaskView.class.getDeclaredMethod("result", JsonObject.class, String.class); method.setAccessible(true);
        var displayed = (JsonObject) method.invoke(null, raw, "/terminal/result");
        check(displayed.get("material_planning_required").getAsBoolean()
                && displayed.getAsJsonObject("planning_handoff").getAsJsonArray("knowledge_uris").size() == 1,
                "material planning facts survive default task compaction");
        // 超大交接也必须首次显示精确缺口，模型无需按历史字段顺序翻页才能知道要加工磨制玫瑰石英。
        var summary = displayed.getAsJsonObject("planning_handoff"); var need = summary.getAsJsonObject("blocked_need");
        check(summary.get("summary_only").getAsBoolean() && need.get("missing").getAsInt() == 3
                && need.getAsJsonArray("item_ids").get(0).getAsString().equals("create:polished_rose_quartz")
                && summary.get("detail_path").getAsString().equals("/terminal/result/data/planning_handoff"),
                "large handoff keeps the actual shortage and exact full-evidence path");
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
}
