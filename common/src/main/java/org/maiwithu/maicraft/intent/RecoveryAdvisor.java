package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

import java.util.List;
import java.util.UUID;

/**
 * Converts every failed internal action into the same semantic recovery protocol.
 *
 * <p>The model never receives body-control or internal-tool choices. It may retry the current
 * intent, insert one semantic prerequisite, skip, or cancel. This keeps recovery extensible
 * without hard-coding one branch for every recipe, block, entity, GUI, or mod.</p>
 */
final class RecoveryAdvisor {

    private RecoveryAdvisor() {}

    static IntentTaskRecord.DecisionSnapshot afterFailure(
            Goal goal, TaskState state, TaskResult result) {
        JsonObject context = baseContext(goal);
        context.addProperty("failure_state", state.name().toLowerCase());
        context.add("failure", resultJson(result));
        return decision(
                "The step could not continue: " + safeMessage(result)
                        + " Choose how MaiCraft should recover before touching the world again.",
                context);
    }

    static IntentTaskRecord.DecisionSnapshot invalidSemanticAnswer(Goal goal, String issue) {
        JsonObject context = baseContext(goal);
        context.addProperty("invalid_answer", issue);
        return decision(
                "That recovery answer was not a valid semantic Goal: " + issue,
                context);
    }

    private static IntentTaskRecord.DecisionSnapshot decision(String question, JsonObject context) {
        return new IntentTaskRecord.DecisionSnapshot(
                UUID.randomUUID(),
                question,
                List.of(
                        new IntentTaskRecord.DecisionOption(
                                "retry",
                                "Retry the same semantic step; details.parameters may refine its intent."),
                        new IntentTaskRecord.DecisionOption(
                                "recover",
                                "Run one semantic prerequisite from details.goal, then retry this step."),
                        new IntentTaskRecord.DecisionOption(
                                "replace_goal",
                                "Replace this step with the semantic Goal in details.goal."),
                        new IntentTaskRecord.DecisionOption(
                                "skip",
                                "Leave this outcome incomplete and continue the remaining sequence."),
                        new IntentTaskRecord.DecisionOption(
                                "cancel",
                                "Cancel the whole task without further world actions.")),
                context.toString());
    }

    private static JsonObject baseContext(Goal goal) {
        JsonObject context = new JsonObject();
        context.addProperty("ability", goal.ability());
        context.addProperty("outcome", goal.outcome());
        context.add("goal", goal.toJson());
        context.addProperty(
                "semantic_goal_rule",
                "details.goal expresses an outcome and constraints; never routes, clicks, slots, or block cells.");
        return context;
    }

    private static JsonObject resultJson(TaskResult result) {
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
