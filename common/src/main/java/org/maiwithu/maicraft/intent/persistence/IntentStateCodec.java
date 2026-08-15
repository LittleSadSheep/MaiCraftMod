// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent.persistence;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.intent.Plan;
import org.maiwithu.maicraft.task.TaskState;

/** Version-one semantic-only JSON codec. It has no representation for native child state. */
public final class IntentStateCodec {
    public static final int MAX_PLANS = 128;
    public static final int MAX_TASKS = 256;
    public static final int MAX_REQUEST_KEYS = 512;
    public static final int MAX_LANDMARKS = 256;
    private static final int MAX_STEPS = 256;
    private static final int MAX_ATTEMPTS = 64;
    private static final int MAX_OPTIONS = 32;
    private static final int MAX_TEXT = 4_096;
    private static final int MAX_RESULT_DEPTH = 32;
    private static final Set<String> INTERNAL_KEYS = Set.of(
            "entity_id", "entity_ids", "requested_entity_ids", "defeated_entity_ids",
            "lost_entity_ids", "unreachable_entity_ids", "combat_by_entity",
            "entity_uuid", "entity_uuids",
            "runtime_id", "runtime_ids", "target_runtime_id", "target_runtime_ids",
            "button", "click", "clicks", "slot", "slots", "inventory_slots",
            "slot_clicks", "click_sequence", "route", "waypoints", "path_nodes",
            "block_ops", "placements", "cells", "receipt", "receipts",
            "exception", "stacktrace", "stack_trace",
            "x", "y", "z", "position", "center", "location", "destination", "bounds");
    private static final Set<String> GOAL_INTERNAL_KEYS = Set.of(
            "entity_id", "entity_ids", "entity_uuid", "entity_uuids",
            "runtime_id", "runtime_ids", "target_runtime_id", "target_runtime_ids",
            "button", "click", "clicks", "slot_clicks", "click_sequence",
            "inventory_slots", "route", "waypoints", "path_nodes", "block_ops",
            "placements", "cells", "receipt", "receipts", "exception",
            "stacktrace", "stack_trace");

    private IntentStateCodec() {}

    public record TaskSnapshot(
            UUID id,
            UUID planId,
            Goal goal,
            List<Goal> steps,
            int stepIndex,
            List<IntentTaskRecord.StepSnapshot> completed,
            Map<Integer, Goal.WorldPosition> internalPositions,
            List<IntentTaskRecord.AttemptSnapshot> attempts,
            IntentTaskRecord.DecisionSnapshot decision,
            IntentTaskRecord.DecisionAnswer pendingAnswer,
            IntentTaskRecord.TerminalSnapshot terminal) {}

    public record Decoded(
            List<Plan> plans,
            List<TaskSnapshot> tasks,
            Map<String, UUID> requestKeys,
            List<IntentRuntime.Landmark> landmarks) {}

    /** Reject semantic input that could not be persisted without truncation or detail loss. */
    public static void requirePersistableGoal(Goal goal) {
        safeGoal(goal);
    }

    public static JsonObject encode(
            String identityKey,
            Iterable<Plan> plans,
            Iterable<IntentTaskRecord> tasks,
            Map<String, UUID> requestKeys,
            Iterable<IntentRuntime.Landmark> landmarks) {
        JsonObject root = new JsonObject();
        root.addProperty("version", IntentStateStore.VERSION);
        root.addProperty("identity_key", identityKey);
        root.addProperty("saved_at_epoch_ms", System.currentTimeMillis());

        JsonArray planArray = new JsonArray();
        int planCount = 0;
        for (Plan plan : plans) {
            if (planCount++ >= MAX_PLANS) break;
            JsonObject value = new JsonObject();
            value.addProperty("id", plan.id().toString());
            value.add("goal", safeGoal(plan.goal()));
            value.addProperty("created_game_time", plan.createdGameTime());
            planArray.add(value);
        }
        root.add("plans", planArray);

        JsonArray taskArray = new JsonArray();
        int taskCount = 0;
        for (IntentTaskRecord task : tasks) {
            if (taskCount++ >= MAX_TASKS) break;
            taskArray.add(encodeTask(task));
        }
        root.add("tasks", taskArray);

        JsonObject keys = new JsonObject();
        int keyCount = 0;
        for (Map.Entry<String, UUID> entry : requestKeys.entrySet()) {
            if (keyCount++ >= MAX_REQUEST_KEYS) break;
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getKey().length() > 256 || entry.getValue() == null) continue;
            keys.addProperty(entry.getKey(), entry.getValue().toString());
        }
        root.add("request_keys", keys);

        JsonArray landmarkArray = new JsonArray();
        int landmarkCount = 0;
        for (IntentRuntime.Landmark landmark : landmarks) {
            if (landmarkCount++ >= MAX_LANDMARKS) break;
            JsonObject value = new JsonObject();
            value.addProperty("label", bounded(landmark.label()));
            value.add("position", worldPosition(landmark.position()));
            landmarkArray.add(value);
        }
        root.add("landmarks", landmarkArray);
        return root;
    }

    private static JsonObject encodeTask(IntentTaskRecord task) {
        JsonObject value = new JsonObject();
        value.addProperty("id", task.externalId().toString());
        if (task.planId() != null) value.addProperty("plan_id", task.planId().toString());
        value.add("goal", safeGoal(task.goal()));
        JsonArray steps = new JsonArray();
        task.steps().stream().limit(MAX_STEPS).forEach(step -> steps.add(safeGoal(step)));
        value.add("steps", steps);
        value.addProperty("step_index", task.stepIndex());

        JsonArray completed = new JsonArray();
        task.stepResults().stream().limit(MAX_STEPS).forEach(step -> {
            JsonObject item = new JsonObject();
            item.addProperty("index", step.index());
            item.addProperty("ability", bounded(step.ability()));
            item.addProperty("success", step.success());
            item.addProperty("message", safeMessage(step.message()));
            item.add("result", safeObject(step.resultJson()));
            completed.add(item);
        });
        value.add("completed_steps", completed);

        // These coordinates are an opaque Mod-owned handoff between semantic children. They are
        // persisted separately from public results and are never emitted by the MCP facade.
        JsonArray internalPositions = new JsonArray();
        task.internalPositionReceipts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(MAX_STEPS)
                .forEach(entry -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("step_index", entry.getKey());
                    item.add("position", worldPosition(entry.getValue()));
                    internalPositions.add(item);
                });
        value.add("internal_positions", internalPositions);

        JsonArray attempts = new JsonArray();
        List<IntentTaskRecord.AttemptSnapshot> attemptValues = task.attempts();
        int from = Math.max(0, attemptValues.size() - MAX_ATTEMPTS);
        for (IntentTaskRecord.AttemptSnapshot attempt : attemptValues.subList(
                from, attemptValues.size())) {
            JsonObject item = new JsonObject();
            item.addProperty("step_index", attempt.stepIndex());
            item.add("goal", safeGoal(attempt.goal()));
            item.addProperty("state", attempt.state().name().toLowerCase());
            item.addProperty("message", safeMessage(attempt.message()));
            item.add("result", safeObject(attempt.resultJson()));
            item.addProperty("game_time", attempt.gameTime());
            attempts.add(item);
        }
        value.add("attempts", attempts);
        if (task.decisionSnapshot() != null) {
            value.add("decision", decision(task.decisionSnapshot()));
        }
        if (task.pendingAnswerSnapshot() != null) {
            IntentTaskRecord.DecisionAnswer answer = task.pendingAnswerSnapshot();
            JsonObject item = new JsonObject();
            item.addProperty("decision_id", answer.decisionId().toString());
            item.addProperty("choice", bounded(answer.choice()));
            item.add("details", safeObject(answer.detailsJson()));
            value.add("pending_answer", item);
        }
        if (task.terminalSnapshot() != null) {
            IntentTaskRecord.TerminalSnapshot terminal = task.terminalSnapshot();
            JsonObject item = new JsonObject();
            item.addProperty("state", terminal.state().name().toLowerCase());
            item.add("result", safeObject(terminal.resultJson()));
            item.addProperty("game_time", terminal.gameTime());
            value.add("terminal", item);
        }
        return value;
    }

    private static JsonObject decision(IntentTaskRecord.DecisionSnapshot decision) {
        JsonObject value = new JsonObject();
        value.addProperty("id", decision.id().toString());
        value.addProperty("question", safeMessage(decision.question()));
        JsonArray options = new JsonArray();
        decision.options().stream().limit(MAX_OPTIONS).forEach(option -> {
            JsonObject item = new JsonObject();
            item.addProperty("choice", bounded(option.choice()));
            item.addProperty("description", bounded(option.description()));
            options.add(item);
        });
        value.add("options", options);
        value.add("context", safeObject(decision.contextJson()));
        return value;
    }

    public static Decoded decode(JsonObject root) {
        List<Plan> plans = new ArrayList<>();
        JsonArray planArray = array(root, "plans", MAX_PLANS);
        for (JsonElement element : planArray) {
            JsonObject value = element.getAsJsonObject();
            Goal goal = decodeGoal(value.getAsJsonObject("goal"));
            plans.add(new Plan(
                    UUID.fromString(text(value, "id")),
                    goal,
                    goal.executableSteps(),
                    longValue(value, "created_game_time", 0L)));
        }

        List<TaskSnapshot> tasks = new ArrayList<>();
        JsonArray taskArray = array(root, "tasks", MAX_TASKS);
        for (JsonElement element : taskArray) {
            tasks.add(decodeTask(element.getAsJsonObject()));
        }

        Map<String, UUID> keys = new LinkedHashMap<>();
        JsonObject keyObject = root.has("request_keys") && root.get("request_keys").isJsonObject()
                ? root.getAsJsonObject("request_keys") : new JsonObject();
        int keyCount = 0;
        for (Map.Entry<String, JsonElement> entry : keyObject.entrySet()) {
            if (keyCount++ >= MAX_REQUEST_KEYS) break;
            if (entry.getKey().isBlank() || entry.getKey().length() > 256
                    || !entry.getValue().isJsonPrimitive()) continue;
            keys.put(entry.getKey(), UUID.fromString(entry.getValue().getAsString()));
        }

        List<IntentRuntime.Landmark> landmarks = new ArrayList<>();
        for (JsonElement element : array(root, "landmarks", MAX_LANDMARKS)) {
            JsonObject value = element.getAsJsonObject();
            landmarks.add(new IntentRuntime.Landmark(
                    bounded(text(value, "label")),
                    decodePosition(value.getAsJsonObject("position"))));
        }
        return new Decoded(
                List.copyOf(plans), List.copyOf(tasks),
                Map.copyOf(keys), List.copyOf(landmarks));
    }

    private static TaskSnapshot decodeTask(JsonObject value) {
        UUID id = UUID.fromString(text(value, "id"));
        UUID planId = value.has("plan_id")
                ? UUID.fromString(value.get("plan_id").getAsString()) : null;
        Goal goal = decodeGoal(value.getAsJsonObject("goal"));
        List<Goal> steps = new ArrayList<>();
        for (JsonElement element : array(value, "steps", MAX_STEPS)) {
            steps.add(decodeGoal(element.getAsJsonObject()));
        }
        if (steps.isEmpty()) steps.addAll(goal.executableSteps());
        int stepIndex = integer(value, "step_index", 0);
        if (stepIndex < 0 || stepIndex > steps.size()) {
            throw new IllegalArgumentException("persisted semantic step index is outside bounds");
        }

        List<IntentTaskRecord.StepSnapshot> completed = new ArrayList<>();
        for (JsonElement element : array(value, "completed_steps", MAX_STEPS)) {
            JsonObject item = element.getAsJsonObject();
            int completedIndex = integer(item, "index", completed.size());
            if (completedIndex < 0 || completedIndex >= steps.size()) {
                throw new IllegalArgumentException(
                        "persisted completed semantic step index is outside bounds");
            }
            completed.add(new IntentTaskRecord.StepSnapshot(
                    completedIndex,
                    bounded(text(item, "ability")),
                    item.has("success") && item.get("success").getAsBoolean(),
                    safeMessage(text(item, "message")),
                    safeElement(item.get("result")).toString()));
        }
        if (completed.size() > stepIndex) {
            throw new IllegalArgumentException(
                    "persisted completed semantic steps exceed current progress");
        }

        Map<Integer, Goal.WorldPosition> internalPositions = new LinkedHashMap<>();
        for (JsonElement element : array(value, "internal_positions", MAX_STEPS)) {
            JsonObject item = element.getAsJsonObject();
            int positionStepIndex = integer(item, "step_index", -1);
            if (positionStepIndex < 0 || positionStepIndex >= stepIndex) {
                throw new IllegalArgumentException(
                        "persisted internal position receipt is outside completed progress");
            }
            if (!item.has("position") || !item.get("position").isJsonObject()) {
                throw new IllegalArgumentException(
                        "persisted internal position receipt has no position");
            }
            if (internalPositions.put(positionStepIndex,
                    decodePosition(item.getAsJsonObject("position"))) != null) {
                throw new IllegalArgumentException(
                        "duplicate persisted internal position receipt");
            }
        }

        List<IntentTaskRecord.AttemptSnapshot> attempts = new ArrayList<>();
        for (JsonElement element : array(value, "attempts", MAX_ATTEMPTS)) {
            JsonObject item = element.getAsJsonObject();
            int attemptStepIndex = integer(item, "step_index", 0);
            if (attemptStepIndex < 0 || attemptStepIndex >= steps.size()) {
                throw new IllegalArgumentException(
                        "persisted semantic attempt index is outside bounds");
            }
            attempts.add(new IntentTaskRecord.AttemptSnapshot(
                    attemptStepIndex,
                    decodeGoal(item.getAsJsonObject("goal")),
                    TaskState.valueOf(text(item, "state").toUpperCase()),
                    safeMessage(text(item, "message")),
                    safeElement(item.get("result")).toString(),
                    longValue(item, "game_time", 0L)));
        }
        IntentTaskRecord.DecisionSnapshot decision = value.has("decision")
                ? decodeDecision(value.getAsJsonObject("decision")) : null;
        IntentTaskRecord.DecisionAnswer answer = value.has("pending_answer")
                ? decodeAnswer(value.getAsJsonObject("pending_answer")) : null;
        IntentTaskRecord.TerminalSnapshot terminal = value.has("terminal")
                ? decodeTerminal(value.getAsJsonObject("terminal")) : null;
        return new TaskSnapshot(
                id, planId, goal, List.copyOf(steps), stepIndex,
                List.copyOf(completed), Map.copyOf(internalPositions),
                List.copyOf(attempts),
                decision, answer, terminal);
    }

    private static IntentTaskRecord.DecisionSnapshot decodeDecision(JsonObject value) {
        List<IntentTaskRecord.DecisionOption> options = new ArrayList<>();
        for (JsonElement element : array(value, "options", MAX_OPTIONS)) {
            JsonObject item = element.getAsJsonObject();
            options.add(new IntentTaskRecord.DecisionOption(
                    bounded(text(item, "choice")),
                    bounded(text(item, "description"))));
        }
        if (options.isEmpty()) throw new IllegalArgumentException(
                "persisted semantic decision has no options");
        return new IntentTaskRecord.DecisionSnapshot(
                UUID.fromString(text(value, "id")),
                safeMessage(text(value, "question")),
                options,
                safeElement(value.get("context")).toString());
    }

    private static IntentTaskRecord.DecisionAnswer decodeAnswer(JsonObject value) {
        return new IntentTaskRecord.DecisionAnswer(
                UUID.fromString(text(value, "decision_id")),
                bounded(text(value, "choice")),
                safeElement(value.get("details")).toString());
    }

    private static IntentTaskRecord.TerminalSnapshot decodeTerminal(JsonObject value) {
        TaskState state = TaskState.valueOf(text(value, "state").toUpperCase());
        if (!state.isTerminal()) throw new IllegalArgumentException(
                "persisted terminal state is not terminal");
        return new IntentTaskRecord.TerminalSnapshot(
                state,
                safeElement(value.get("result")).toString(),
                longValue(value, "game_time", 0L));
    }

    private static JsonArray array(JsonObject root, String key, int maximum) {
        if (!root.has(key)) return new JsonArray();
        if (!root.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " must be an array");
        }
        JsonArray array = root.getAsJsonArray(key);
        if (array.size() > maximum) {
            throw new IllegalArgumentException(key + " exceeds its persisted bound");
        }
        return array;
    }

    private static JsonObject worldPosition(Goal.WorldPosition position) {
        JsonObject value = new JsonObject();
        value.addProperty("x", position.x());
        value.addProperty("y", position.y());
        value.addProperty("z", position.z());
        if (position.dimension() != null) {
            value.addProperty("dimension", bounded(position.dimension()));
        }
        return value;
    }

    private static Goal.WorldPosition decodePosition(JsonObject value) {
        return new Goal.WorldPosition(
                integer(value, "x", 0),
                integer(value, "y", 0),
                integer(value, "z", 0),
                value.has("dimension") && value.get("dimension").isJsonPrimitive()
                        ? bounded(value.get("dimension").getAsString()) : null);
    }

    private static JsonObject safeGoal(Goal goal) {
        JsonObject original = goal.toJson();
        JsonElement safe = safeGoalElement(original, 0);
        if (!safe.isJsonObject()) {
            throw new IllegalArgumentException("semantic goal must encode as an object");
        }
        if (!safe.equals(original)) {
            throw new IllegalArgumentException(
                    "semantic goal contains native execution details or exceeds persistence bounds");
        }
        return safe.getAsJsonObject();
    }

    private static Goal decodeGoal(JsonObject value) {
        JsonElement safe = safeGoalElement(value, 0);
        if (!safe.equals(value)) {
            throw new IllegalArgumentException(
                    "persisted semantic goal contains native execution details or exceeds bounds");
        }
        return Goal.fromJson(value);
    }

    private static JsonObject safeObject(String json) {
        try {
            JsonElement element = JsonParser.parseString(
                    json == null || json.isBlank() ? "{}" : json);
            JsonElement safe = safeElement(element);
            return safe.isJsonObject() ? safe.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException invalid) {
            return new JsonObject();
        }
    }

    private static JsonElement safeElement(JsonElement value) {
        return safeElement(value, 0);
    }

    private static JsonElement safeElement(JsonElement value, int depth) {
        if (value == null || value.isJsonNull() || depth > MAX_RESULT_DEPTH) {
            return JsonNull.INSTANCE;
        }
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isString()) return new JsonPrimitive(safeMessage(primitive.getAsString()));
            return primitive.deepCopy();
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            int count = 0;
            for (JsonElement element : value.getAsJsonArray()) {
                if (count++ >= 256) break;
                result.add(safeElement(element, depth + 1));
            }
            return result;
        }
        JsonObject result = new JsonObject();
        int count = 0;
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            if (count++ >= 256) break;
            String key = entry.getKey();
            if (isInternalKey(key)) continue;
            result.add(bounded(key), safeElement(entry.getValue(), depth + 1));
        }
        return result;
    }

    private static boolean isInternalKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (INTERNAL_KEYS.contains(lower)) return true;
        return lower.endsWith("_entity_id") || lower.endsWith("_entity_ids")
                || lower.endsWith("_entity_uuid") || lower.endsWith("_entity_uuids")
                || lower.endsWith("_runtime_id") || lower.endsWith("_runtime_ids")
                || lower.endsWith("_slot") || lower.endsWith("_slots")
                || lower.endsWith("_click") || lower.endsWith("_clicks")
                || lower.endsWith("_route") || lower.endsWith("_waypoints")
                || lower.endsWith("_path_nodes") || lower.endsWith("_receipt")
                || lower.endsWith("_receipts") || lower.endsWith("_position")
                || lower.endsWith("_center") || lower.endsWith("_location")
                || lower.endsWith("_destination") || lower.endsWith("_bounds")
                || lower.endsWith("_x") || lower.endsWith("_y")
                || lower.endsWith("_z");
    }

    /** Goal metadata may contain semantic equipment slots; only native menu slots are forbidden. */
    private static JsonElement safeGoalElement(JsonElement value, int depth) {
        if (value == null || value.isJsonNull() || depth > MAX_RESULT_DEPTH) {
            return JsonNull.INSTANCE;
        }
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isString()) return new JsonPrimitive(bounded(primitive.getAsString()));
            return primitive.deepCopy();
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            int count = 0;
            for (JsonElement element : value.getAsJsonArray()) {
                if (count++ >= 256) break;
                result.add(safeGoalElement(element, depth + 1));
            }
            return result;
        }
        JsonObject result = new JsonObject();
        int count = 0;
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            if (count++ >= 256) break;
            String key = entry.getKey();
            if (isGoalInternalKey(key)) continue;
            result.add(bounded(key), safeGoalElement(entry.getValue(), depth + 1));
        }
        return result;
    }

    private static boolean isGoalInternalKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (GOAL_INTERNAL_KEYS.contains(lower)) return true;
        return lower.endsWith("_entity_id") || lower.endsWith("_entity_ids")
                || lower.endsWith("_entity_uuid") || lower.endsWith("_entity_uuids")
                || lower.endsWith("_runtime_id") || lower.endsWith("_runtime_ids")
                || lower.endsWith("_click") || lower.endsWith("_clicks")
                || lower.endsWith("_route") || lower.endsWith("_waypoints")
                || lower.endsWith("_path_nodes") || lower.endsWith("_receipt")
                || lower.endsWith("_receipts");
    }

    private static String safeMessage(String value) {
        String message = bounded(value);
        String lower = message.toLowerCase();
        if (lower.contains("exception") || lower.contains("stack trace")
                || lower.contains("internal error") || lower.contains("task start failed")
                || lower.contains("task tick failed") || lower.contains("task result failed")
                || lower.contains("internal tool") || lower.contains("java.")
                || lower.contains("net.minecraft.") || lower.contains("org.maiwithu.")) {
            return "A previous semantic action stopped safely; re-observe current facts before retrying.";
        }
        return message;
    }

    private static String bounded(String value) {
        if (value == null) return "";
        String result = value.strip();
        return result.length() <= MAX_TEXT ? result : result.substring(0, MAX_TEXT);
    }

    private static String text(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return bounded(value.get(key).getAsString());
    }

    private static int integer(JsonObject value, String key, int fallback) {
        if (!value.has(key)) return fallback;
        return value.get(key).getAsInt();
    }

    private static long longValue(JsonObject value, String key, long fallback) {
        if (!value.has(key)) return fallback;
        return value.get(key).getAsLong();
    }
}
