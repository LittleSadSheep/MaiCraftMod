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
        check(!compact.has("goal") && !compact.has("attempts") && compact.get("attempt_count").getAsInt() == 64, "history is counted instead of replayed");
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
        System.out.println("TaskViewTest: full=" + full.toString().length() + " chars; status=" + compact.toString().length() + " chars; passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
