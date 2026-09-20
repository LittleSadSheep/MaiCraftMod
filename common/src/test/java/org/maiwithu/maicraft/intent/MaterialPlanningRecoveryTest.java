// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.maiwithu.maicraft.core.task.acquire.MaterialProcessPlanning;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 材料知识交接继续使用现有恢复序列；允许新库存重估，保留真实不确定动作的重试禁令与原目标保护范围。 */
public final class MaterialPlanningRecoveryTest {
    private static final String URI = "maicraft://knowledge/recipes/minecraft/quartz?direction=output&offset=0&limit=8";

    public static void main(String[] args) throws Exception {
        Goal goal = originalGoal(); TaskResult failure = failure(false);
        var decision = RecoveryAdvisor.afterFailure(goal, TaskState.FAILED, failure);
        JsonObject context = JsonParser.parseString(decision.contextJson()).getAsJsonObject();
        check(decision.options().getFirst().choice().equals("recover") && hasRetry(decision),
                "工艺前提优先于同事实循环，但安全库存重查或新参数retry仍存在");
        check(context.get("decision_kind").getAsString().equals(MaterialProcessPlanning.KIND)
                        && context.get("ordinary_retry_allowed").getAsBoolean() && !decision.question().contains("outcome may be uncertain"),
                "知识缺口不能伪装成已消费结果不确定");
        check(context.getAsJsonObject("goal").equals(goal.toJson())
                        && context.get("planning_rule").getAsString().contains("allow_use"),
                "外部规划得到原目标，并明确仍受各能力实物权限约束");
        var invalid = RecoveryAdvisor.invalidSemanticAnswer(goal, "invalid prerequisite", json(failure));
        check(invalid.options().getFirst().choice().equals("recover") && hasRetry(invalid), "格式错误重问不丢失规划类型和安全重试能力");
        var uncertain = RecoveryAdvisor.afterFailure(goal, TaskState.FAILED, failure(true));
        check(!hasRetry(uncertain) && !RecoveryAdvisor.ordinaryRetryAllowed(failure(true))
                        && !RecoveryAdvisor.ordinaryRetryAllowed(json(failure(true))),
                "真实outcome_uncertain仍优先，知识交接不能放开既有禁止重发边界");
        sequenceReturnsToInventoryGoal(goal); attentionKeepsEvidence(failure);
        System.out.println("MaterialPlanningRecoveryTest: existing sequence recovery, safe retry semantics and compact knowledge handoff passed");
    }

    private static void sequenceReturnsToInventoryGoal(Goal goal) {
        var record = new IntentTaskRecord(UUID.randomUUID(), null, goal);
        Goal first = waiting("等待前置现场事实"), second = waiting("等待前置产物核验");
        Goal prerequisites = new Goal("maicraft:sequence", "先完成被授权的工艺前提", null, "{}", "{}", List.of(), List.of(first, second));
        JsonObject details = new JsonObject(); details.add("goal", prerequisites.toJson());
        // 使用现有details.goal/sequence解析与插入，不创建另一套配方编排语法，也不重写原获取参数。
        record.insertRecovery(Goal.fromJson(details.getAsJsonObject("goal")));
        check(record.steps().size() == 3 && record.steps().getLast().toJson().equals(goal.toJson()), "前置序列后必须仍是同一最终库存目标");
        check(record.steps().getFirst().inheritedProtectionLabels().contains("Farm"), "恢复序列继承原有保护范围");
        while (record.stepIndex() < 2) {
            Goal step = record.steps().get(record.stepIndex());
            record.addStepResult(new IntentTaskRecord.StepSnapshot(record.stepIndex(), step.ability(), true, "prerequisite fixture complete", "{}"));
        }
        check(record.steps().get(record.stepIndex()).parameters().equals(goal.parameters()), "前置完成只让原目标重查库存，不另加机器权限或放大数量");
    }

    private static void attentionKeepsEvidence(TaskResult failure) throws Exception {
        var compact = IntentRuntime.class.getDeclaredMethod("compactAttentionResult", JsonObject.class); compact.setAccessible(true);
        JsonObject result = (JsonObject) compact.invoke(null, json(failure)); JsonObject data = result.getAsJsonObject("data");
        check(data.has("blocked_need") && data.has("planning_handoff")
                        && data.getAsJsonObject("planning_handoff").getAsJsonArray("knowledge_uris").get(0).getAsString().equals(URI),
                "外部attention摘要要保留材料缺口与按需链接，不能只剩一句失败");
        check(data.has("outcome_uncertain") && !data.get("outcome_uncertain").getAsBoolean()
                        && data.get("mechanical_retry_allowed").getAsBoolean(), "摘要保留原来的真实重试安全标记");
        check(!result.toString().contains("display_recipes"), "摘要不嵌入配方正文");
    }

    private static TaskResult failure(boolean uncertain) {
        var handoff = Map.of("kind", MaterialProcessPlanning.KIND, "knowledge_only", true,
                "knowledge_uris", List.of(URI), "machine_route_established", false,
                "recipe_query_evidence", List.of(Map.of("item_id", "minecraft:quartz", "status", "not_loaded", "knowledge_uri", URI)));
        var data = new LinkedHashMap<String, Object>(); data.put("failure_code", MaterialProcessPlanning.FAILURE_CODE);
        data.put("blocked_need", Map.of("item_ids", List.of("minecraft:quartz"), "required_final_count", 3, "observed_final_count", 0));
        data.put("planning_handoff", handoff); data.put("outcome_uncertain", uncertain); data.put("mechanical_retry_allowed", !uncertain);
        return TaskResult.fail("ordinary material sources exhausted", data);
    }
    private static Goal originalGoal() {
        return new Goal("maicraft:acquire_items", "背包里有两个钻石", null,
                "{\"item_id\":\"minecraft:diamond\",\"count\":2,\"allowed_sources\":[\"craft\"]}", "{}", List.of(), List.of(), List.of("Farm"));
    }
    private static Goal waiting(String outcome) { return new Goal("maicraft:wait_for_condition", outcome, null, "{\"condition\":\"daytime\"}", "{}", List.of(), List.of()); }
    private static JsonObject json(TaskResult result) { return JsonParser.parseString(result.toJson()).getAsJsonObject(); }
    private static boolean hasRetry(IntentTaskRecord.DecisionSnapshot decision) { return decision.options().stream().anyMatch(option -> option.choice().equals("retry")); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
