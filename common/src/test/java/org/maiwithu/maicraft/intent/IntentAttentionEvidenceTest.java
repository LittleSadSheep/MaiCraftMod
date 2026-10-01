// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 施工停止后，第一份通知必须携带已有现场，不能靠额外查询找回被摘要丢掉的恢复事实。 */
public final class IntentAttentionEvidenceTest {
    public static void main(String[] args) {
        String detail = "原生机器仍在运行，等待核对已消耗的物品。".repeat(100);
        JsonArray facts = new JsonArray();
        for (int index = 0; index < 24; index++) {
            JsonObject row = new JsonObject();
            row.addProperty("item_id", "minecraft:stone"); row.addProperty("count", index);
            row.addProperty("reason", detail); facts.add(row);
        }
        JsonObject scene = JsonParser.parseString("""
                {"snapshot_id":"latest","blocks":[{"position":{"x":1,"y":2,"z":3},"block_id":"minecraft:chest"}]}
                """).getAsJsonObject();
        var failure = SemanticResultView.result(TaskResult.fail(detail, Map.of(
                "failure_code", "native_transfer_pending", "latest_snapshot", scene,
                "actual_inventory", facts, "issues", facts, "outcome_uncertain", true,
                "mechanical_retry_allowed", false, "observed_quote", Map.of("reason", detail),
                "route", List.of("internal navigation"), "server_supply_receipts", facts,
                "confirmed_harvests", IntStream.range(0, 40).mapToObj(index -> Map.of("block_id", "minecraft:stone",
                        "position", Map.of("x", index, "y", 10, "z", 0))).toList())));
        Goal goal = new Goal("maicraft:acquire_items", "取回机器产物", null, "{}", "{}", List.of(), List.of());
        var record = new IntentTaskRecord(UUID.randomUUID(), null, goal);
        var runtime = IntentRuntime.get();
        long cursor = runtime.attentionCheckpoint().get("cursor").getAsLong();
        runtime.terminal(record, TaskState.FAILED, failure);
        JsonObject result = runtime.attention(cursor, 20).getAsJsonArray("events")
                .get(0).getAsJsonObject().getAsJsonObject("data");
        JsonObject data = result.getAsJsonObject("data");
        // 真实观察、长诊断和全部材料行都随失败到达；失败及禁止重复消费的标记不因补齐证据而改变。
        check(result.get("message").getAsString().equals(detail), "失败说明不得截断");
        check(data.get("latest_snapshot").equals(scene), "通知必须带出刚补读的现场");
        check(data.get("actual_inventory").equals(facts) && data.get("issues").equals(facts), "全部材料和问题必须保留");
        check(data.get("server_supply_receipts").equals(facts) && data.getAsJsonArray("confirmed_harvests").size() == 40,
                "原生供料回执与全部已采方块不得在语义层被删除");
        check(data.getAsJsonObject("observed_quote").get("reason").getAsString().equals(detail), "原生报价无需另查");
        check(!result.get("success").getAsBoolean() && data.get("outcome_uncertain").getAsBoolean()
                && !data.get("mechanical_retry_allowed").getAsBoolean(), "保留真实失败与消费不确定性");
        check(!data.has("route"), "内部导航脚本仍由 Mod 持有");
        // 玩家叫停任务时，完整材料事实与取消来源一起经过语义包装和通知，不能被新旧回执字段覆盖。
        cursor = runtime.attentionCheckpoint().get("cursor").getAsLong();
        var cancelled = SemanticResultView.result(TaskResult.cancelled("玩家接管机器操作", "operator_cancel")
                .withData(Map.of("actual_inventory", facts)));
        runtime.terminal(record, TaskState.CANCELLED, cancelled);
        result = runtime.attention(cursor, 20).getAsJsonArray("events").get(0).getAsJsonObject().getAsJsonObject("data");
        check(result.get("interrupted").getAsBoolean() && result.get("cancel_source").getAsString().equals("operator_cancel")
                && result.getAsJsonObject("data").get("actual_inventory").equals(facts), "取消通知同时保留来源与全部已知材料");
        System.out.println("IntentAttentionEvidenceTest: passed");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
