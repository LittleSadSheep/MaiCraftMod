package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一步做不下去时，把失败原因变成调用者能回答的问题：再试一次、先补条件、改做别的、跳过或取消。
 * 例如造炉子缺石头，可以回答“先找石头”，而不必让调用者指挥每次鼠标点击。
 */
final class RecoveryAdvisor {

    private RecoveryAdvisor() {}

    static IntentTaskRecord.DecisionSnapshot afterFailure(
            Goal goal, TaskState state, TaskResult result) {
        // 先把这次失败的原始结果带上，再决定能否提供“直接重试”选项。
        JsonObject context = baseContext(goal);
        context.addProperty("failure_state", state.name().toLowerCase());
        context.add("failure", resultJson(result));
        boolean retryAllowed = ordinaryRetryAllowed(result);
        addRetrySafety(context, retryAllowed);
        return decision(
                "The step could not continue: " + safeMessage(result)
                        + (retryAllowed
                        ? " Choose how MaiCraft should recover before touching the world again."
                        : " Its outcome may be uncertain or unsafe to repeat. Inspect current facts, then recover, replace, skip or cancel before touching the world again."),
                context,
                retryAllowed);
    }

    static IntentTaskRecord.DecisionSnapshot invalidSemanticAnswer(
            Goal goal, String issue, JsonObject priorFailure) {
        // 答复格式不对时继续询问，并保留上次失败背景；不能因为重新提问就放开原先禁止的重试。
        JsonObject context = baseContext(goal);
        context.addProperty("invalid_answer", issue);
        boolean retryAllowed = ordinaryRetryAllowed(priorFailure);
        if (priorFailure != null) context.add("failure", priorFailure.deepCopy());
        addRetrySafety(context, retryAllowed);
        return decision(
                "That recovery answer was not a valid semantic Goal: " + issue,
                context,
                retryAllowed);
    }

    static IntentTaskRecord.DecisionSnapshot retryRefused(Goal goal, JsonObject priorFailure) {
        JsonObject context = baseContext(goal);
        if (priorFailure != null) context.add("failure", priorFailure.deepCopy());
        addRetrySafety(context, false);
        return decision(
                "Ordinary retry is unavailable because the previous outcome may be uncertain or unsafe to repeat. Inspect current facts, then choose a semantic recovery, replacement, skip or cancellation.",
                context,
                false);
    }

    static boolean ordinaryRetryAllowed(TaskResult result) {
        // 当前只认这两个标记：结果不确定，或机械动作明确不许重试；标记缺失时默认允许。
        if (result == null || result.data() == null) return true;
        Map<String, Object> data = result.data();
        return !Boolean.TRUE.equals(asBoolean(data.get("outcome_uncertain")))
                && !Boolean.FALSE.equals(asBoolean(data.get("mechanical_retry_allowed")));
    }

    static boolean ordinaryRetryAllowed(JsonObject result) {
        if (result == null || !result.has("data") || !result.get("data").isJsonObject()) {
            return true;
        }
        JsonObject data = result.getAsJsonObject("data");
        return !jsonBoolean(data, "outcome_uncertain", false)
                && jsonBoolean(data, "mechanical_retry_allowed", true);
    }

    private static IntentTaskRecord.DecisionSnapshot decision(
            String question, JsonObject context, boolean retryAllowed) {
        // 不确定上次到底做了多少时，去掉直接重试；仍允许在看过现状后换目标、补前提或叫停。
        List<IntentTaskRecord.DecisionOption> options = new ArrayList<>();
        if (retryAllowed) {
            options.add(new IntentTaskRecord.DecisionOption(
                    "retry",
                    "Retry the same semantic step; details.parameters may refine its intent."));
        }
        options.addAll(List.of(
                new IntentTaskRecord.DecisionOption(
                        "recover",
                        "After inspecting current facts, run one semantic prerequisite from details.goal, then reassess this step."),
                new IntentTaskRecord.DecisionOption(
                        "replace_goal",
                        "Replace this step with the semantic Goal in details.goal."),
                new IntentTaskRecord.DecisionOption(
                        "skip",
                        "Leave this outcome incomplete and continue the remaining sequence."),
                new IntentTaskRecord.DecisionOption(
                        "cancel",
                        "Cancel the whole task without further world actions.")));
        return new IntentTaskRecord.DecisionSnapshot(
                UUID.randomUUID(),
                question,
                options,
                context.toString());
    }

    private static void addRetrySafety(JsonObject context, boolean allowed) {
        context.addProperty("ordinary_retry_allowed", allowed);
        if (!allowed) {
            context.addProperty("inspection_required", true);
            context.addProperty(
                    "retry_rule",
                    "Do not repeat the prior mechanical action. Inspect current facts and choose a semantic recovery, replacement, skip or cancellation.");
        }
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean flag) return flag;
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) return true;
            if ("false".equalsIgnoreCase(text)) return false;
        }
        return null;
    }

    private static boolean jsonBoolean(JsonObject object, String key, boolean fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive()) return fallback;
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static JsonObject baseContext(Goal goal) {
        JsonObject context = new JsonObject();
        context.addProperty("ability", goal.ability());
        context.addProperty("outcome", goal.outcome());
        context.add("goal", goal.toJson());
        context.addProperty(
                "semantic_goal_rule",
                "details.goal uses the selected ability's declared fields. Machine review/build may supply a semantic design, explicit blueprint or exported Ponder blueprint_uri; modify_machine can apply blueprint changes. Use receipt-bound observed entry_index values for machine menu operations. MaiCraft owns native routes and gestures; never provide click scripts.");
        return context;
    }

    private static JsonObject resultJson(TaskResult result) {
        // 失败结果本身也可能无法转成 JSON，此时至少保留失败说明，确保还能向调用者提问。
        try {
            return JsonParser.parseString(result.toJson()).getAsJsonObject();
        } catch (RuntimeException ignored) {
            JsonObject fallback = new JsonObject();
            fallback.addProperty("success", false);
            fallback.addProperty("message", safeMessage(result));
            return fallback;
        }
    }

    private static String safeMessage(TaskResult result) {
        String message = result == null ? null : result.message();
        return message == null || message.isBlank() ? "internal action failed" : message;
    }
}
