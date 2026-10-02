// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 恢复知识必须随真实失败事实进入归档与通知，不能改写消费未知、原生建议或成功动作。 */
public final class RecoveryKnowledgeTest {
    public static void main(String[] args) {
        var goal = new Goal("maicraft:acquire_items", "取得缺少的材料", null, "{}", "{}", List.of(), List.of());
        var source = TaskResult.cancelled("尚有原生消费未结清", "operator").withData(Map.of(
                "blocked_need", Map.of("item_ids", List.of("minecraft:iron_ingot"), "missing", 2),
                "completed_effects", List.of(Map.of("item_id", "minecraft:iron_ingot", "count", 1)),
                "outcome_uncertain", true, "mechanical_retry_allowed", false,
                "latest_snapshot", Map.of("snapshot_id", "already-returned", "structure_complete", false),
                "recovery_options", List.of(Map.of("id", "prepare_inventory_capacity", "risk", "existing_authorization_required"))));
        var result = RecoveryKnowledge.attach(goal, source);
        var json = JsonParser.parseString(result.toJson()).getAsJsonObject(); var data = json.getAsJsonObject("data");
        var options = data.getAsJsonArray("recovery_options"); var knowledge = options.get(0).getAsJsonObject();
        check(!result.success() && result.interrupted() && result.cancelSource().equals("operator")
                && data.get("outcome_uncertain").getAsBoolean() && !data.get("mechanical_retry_allowed").getAsBoolean(),
                "knowledge keeps settlement and unknown-consumption markers intact");
        check(options.get(1).equals(JsonParser.parseString(source.toJson()).getAsJsonObject().getAsJsonObject("data")
                .getAsJsonArray("recovery_options").get(0)), "existing native recovery option remains unchanged");
        check(knowledge.getAsJsonArray("knowledge").asList().stream().anyMatch(value -> value.getAsJsonObject()
                .get("resource_uri").getAsString().endsWith("minecraft/iron_ingot")), "exact blocked material has a direct recipe entry");
        for (String group : List.of("knowledge", "ability_contracts")) for (var hint : knowledge.getAsJsonArray(group)) {
            JsonObject read = hint.getAsJsonObject().getAsJsonObject("read_arguments");
            if (read.get("view").getAsString().equals("knowledge"))
                check(KnowledgeLibrary.perceptionRequest(read).get("action").getAsString().equals("read"), "knowledge reference selects a document");
            else check(IntentRuntime.KNOWN_ABILITIES.contains(read.get("focus").getAsString()), "ability reference selects an existing contract");
            check(!read.has("goal") && !read.get("view").getAsString().equals("surroundings"), "recovery hints only read knowledge, never repeat world actions or surveys");
            check(!read.has("focus") || !read.get("focus").getAsString().equals("maicraft:inspect_machine"), "returned snapshot suppresses unnecessary inspection advice");
        }
        check(knowledge.getAsJsonObject("evidence_fields").getAsJsonArray("effects").toString().contains("completed_effects"),
                "confirmed effects and remaining need are located in the same receipt");
        check(RecoveryKnowledge.attach(goal, result).toJson().equals(result.toJson()), "repeated presentation does not accumulate advice");
        var success = TaskResult.ok("原生动作完成，结构差异由模型判断", Map.of("blueprint_diff", Map.of("structure_matches_blueprint", false)));
        check(RecoveryKnowledge.attach(goal, success) == success, "a structural mismatch never turns a successful action into recovery failure");
        // 失败提示跟随任务快照和 Attention 通知；持久化的是这次回执本身，不依赖后来重新查世界。
        var record = new IntentTaskRecord(UUID.randomUUID(), null, goal);
        record.terminal(TaskState.FAILED, result, 41L);
        check(record.terminalSnapshot().result().getAsJsonObject("data").get("recovery_options").equals(options), "frozen task snapshot retains direct references");
        var runtime = IntentRuntime.get(); long cursor = runtime.attentionCheckpoint().get("cursor").getAsLong();
        runtime.terminal(record, TaskState.FAILED, result);
        var delivered = runtime.attention(cursor, 20).getAsJsonArray("events").get(0).getAsJsonObject().getAsJsonObject("data");
        check(delivered.getAsJsonObject("data").get("recovery_options").equals(options), "default failure notification preserves full structured recovery hints");
        System.out.println("RecoveryKnowledgeTest: passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
