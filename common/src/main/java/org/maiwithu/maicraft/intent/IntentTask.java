package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.ToolRegistry;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskDispatch;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A semantic parent task. Internal body tools become child tasks and are driven
 * here, so the parent remains the only selected scheduler winner.
 */
final class IntentTask implements Task {

    private static final Set<String> INTERNAL_RESULT_KEYS = Set.of(
            "entity_id", "entity_ids", "requested_entity_ids", "defeated_entity_ids",
            "lost_entity_ids", "unreachable_entity_ids", "combat_by_entity",
            "runtime_id", "runtime_ids", "target_runtime_id", "target_runtime_ids",
            "button", "click", "clicks", "slot", "slots", "inventory_slots",
            "slot_clicks", "click_sequence", "route", "waypoints", "path_nodes",
            "block_ops", "placements", "cells", "continuation_token",
            "continuation_prefix_hash");

    private final LocalPlayer player;
    private final IntentTaskRecord record;
    private final IntentRuntime runtime;

    private Task child;
    private TaskRecord childRecord;
    private IntentAction.Wait wait;
    private List<IntentAction.Tool> chain = List.of();
    private int chainIndex;
    private TaskResult terminalResult;
    private boolean terminalPublished;
    private long childSerial;
    /** Opaque one-use receipts stay inside the Mod; the model only chooses semantic retry. */
    private final Map<String, UUID> mechanicalContinuations = new LinkedHashMap<>();
    /** Verified positions retained inside the semantic parent, never rendered into tool results. */
    private final Map<Integer, Goal.WorldPosition> internalStepPositions = new LinkedHashMap<>();

    IntentTask(LocalPlayer player, IntentTaskRecord record, IntentRuntime runtime) {
        this.player = player;
        this.record = record;
        this.runtime = runtime;
    }

    @Override
    public boolean canRun(LocalPlayer ignored) {
        return !record.paused();
    }

    @Override
    public TaskState tick(LocalPlayer ignored) {
        try {
            return tickSemanticParent();
        } catch (RuntimeException failure) {
            // A malformed adapter result or unexpected internal capability failure must
            // still enter the semantic recovery protocol. Fatal VM errors are deliberately
            // not caught here.
            abandonChildAfterUnexpectedFailure();
            wait = null;
            return failStep(TaskState.FAILED,
                    TaskResult.fail("semantic step failed safely: " + safeMessage(failure)));
        }
    }

    private TaskState tickSemanticParent() {
        if (record.stepIndex() >= record.steps().size()) {
            terminalResult = successResult();
            return TaskState.SUCCESS;
        }

        IntentTaskRecord.DecisionAnswer answer = record.takeAnswer();
        if (answer != null) {
            if ("cancel".equals(answer.choice()) || "cancel_task".equals(answer.choice())) {
                mechanicalContinuations.clear();
                terminalResult = TaskResult.cancelled("cancelled at decision " + answer.decisionId());
                return TaskState.CANCELLED;
            }
            if ("respawn".equals(answer.choice()) || "spectate".equals(answer.choice())) {
                // Death-screen actions are executed natively by the runtime facade.  Once a
                // restored task is explicitly resumed, re-evaluate its semantic step instead of
                // passing a transport/lifecycle answer to an ability adapter.
                return TaskState.RUNNING;
            }
            if ("skip".equals(answer.choice())) {
                discardContinuation(currentGoal());
                completeStep(TaskResult.ok("step skipped by explicit decision"));
                return record.stepIndex() >= record.steps().size() ? TaskState.SUCCESS : TaskState.RUNNING;
            }
            if ("recover".equals(answer.choice()) || "replace_goal".equals(answer.choice())) {
                if ("replace_goal".equals(answer.choice())) discardContinuation(currentGoal());
                return applySemanticAnswer(answer);
            }
            IntentAction resolved = AbilityAdapter.fromAnswer(
                    resolvedCurrentGoal(), answer, player, runtime,
                    continuationFor(currentGoal()));
            return begin(resolved);
        }

        if (child != null) {
            return tickChild();
        }
        if (wait != null) {
            return tickWait();
        }
        if (!chain.isEmpty()) {
            return beginTool(chain.get(chainIndex));
        }

        Goal semanticGoal = currentGoal();
        return begin(AbilityAdapter.adapt(
                resolvedCurrentGoal(), player, runtime, continuationFor(semanticGoal)));
    }

    private TaskState begin(IntentAction action) {
        if (action instanceof IntentAction.Chain nextChain) {
            chain = nextChain.actions();
            chainIndex = 0;
            return beginTool(chain.getFirst());
        }
        if (action instanceof IntentAction.Decision decision) {
            return requestDecision(decision.snapshot());
        }
        if (action instanceof IntentAction.Remember remember) {
            runtime.remember(remember.label(), remember.position());
            completeStep(TaskResult.ok("remembered " + remember.label(),
                    Map.of("label", remember.label(),
                            "x", remember.position().x(),
                            "y", remember.position().y(),
                            "z", remember.position().z())));
            return afterImmediate();
        }
        if (action instanceof IntentAction.Wait nextWait) {
            wait = nextWait;
            return tickWait();
        }
        if (action instanceof IntentAction.Tool toolAction) {
            return beginTool(toolAction);
        }
        return failStep(
                TaskState.FAILED,
                TaskResult.fail("intent adapter produced no executable action"));
    }

    private TaskState beginTool(IntentAction.Tool action) {
        MaiCraftTool tool = ToolRegistry.resolve(action.toolName());
        if (tool == null) {
            return failStep(
                    TaskState.FAILED,
                    TaskResult.fail("required internal capability is not registered: "
                            + action.toolName()));
        }

        AtomicReference<TaskRecord> captured = new AtomicReference<>();
        AtomicReference<String> immediate = new AtomicReference<>();
        String childCallId = "intent-" + record.externalId() + "-"
                + record.stepIndex() + "-" + (++childSerial);
        try {
            TaskDispatch.captureNext(captured::set, () ->
                    tool.onGameCall(childCallId, action.arguments(), player, immediate::set));
        } catch (RuntimeException exception) {
            return failStep(
                    TaskState.FAILED,
                    TaskResult.fail("ability " + currentGoal().ability()
                            + " could not start: " + safeMessage(exception)));
        }

        if (captured.get() != null) {
            childRecord = captured.get();
            childRecord.setState(TaskState.RUNNING);
            childRecord.markStarted(player.level().getGameTime());
            child = TaskFactory.create(player, childRecord);
            try {
                child.start(player);
            } catch (RuntimeException exception) {
                childRecord.setState(TaskState.FAILED);
                try {
                    child.result(TaskState.FAILED);
                } catch (RuntimeException ignoredFailure) {
                }
                TaskResult failure = TaskResult.fail(
                        "child start failed: " + safeMessage(exception));
                clearChild();
                return failStep(TaskState.FAILED, failure);
            }
            if (childRecord.getState().isTerminal()) {
                return finishChild();
            }
            return TaskState.RUNNING;
        }

        if (immediate.get() != null) {
            TaskResult result = parseImmediate(immediate.get());
            if (!result.success()) return failStep(TaskState.FAILED, result);
            return finishToolSuccess(result);
        }

        return failStep(
                TaskState.FAILED,
                TaskResult.fail("internal capability produced neither a child task nor a result: "
                        + action.toolName()));
    }

    private TaskState tickChild() {
        if (player.level().getGameTime() >= childRecord.getDeadlineGameTime()) {
            childRecord.setState(TaskState.TIMEOUT);
        } else {
            try {
                childRecord.setState(child.tick(player));
            } catch (RuntimeException exception) {
                childRecord.setState(TaskState.FAILED);
                childRecord.setResult(TaskResult.fail(
                        "child tick failed: " + safeMessage(exception)));
            }
        }
        return childRecord.getState().isTerminal() ? finishChild() : TaskState.RUNNING;
    }

    private TaskState finishChild() {
        TaskState state = childRecord.getState();
        TaskResult result;
        try {
            result = child.result(state);
        } catch (RuntimeException exception) {
            result = TaskResult.fail("child result failed: " + safeMessage(exception));
            state = TaskState.FAILED;
        }
        if (result == null) result = defaultResult(state);
        InternalPositionReceipt.Position internalPosition = result.success()
                && childRecord instanceof InternalPositionReceipt receipt
                ? receipt.internalVerifiedPosition() : null;
        if (internalPosition != null) {
            internalStepPositions.put(record.stepIndex(), new Goal.WorldPosition(
                    internalPosition.x(), internalPosition.y(), internalPosition.z(),
                    internalPosition.dimension()));
        }
        clearChild();
        if (!result.success()) {
            return failStep(state, result);
        }
        return finishToolSuccess(result);
    }

    private TaskState finishToolSuccess(TaskResult result) {
        if (!chain.isEmpty()) {
            chainIndex++;
            if (chainIndex < chain.size()) return TaskState.RUNNING;
            chain = List.of();
            chainIndex = 0;
        }
        discardContinuation(currentGoal());
        completeStep(result);
        return afterImmediate();
    }

    private TaskState applySemanticAnswer(IntentTaskRecord.DecisionAnswer answer) {
        JsonObject details = answer.details();
        if (!details.has("goal") || !details.get("goal").isJsonObject()) {
            return requestDecision(RecoveryAdvisor.invalidSemanticAnswer(
                    currentGoal(), answer.choice() + " requires details.goal"));
        }
        Goal semanticGoal;
        try {
            semanticGoal = Goal.fromJson(details.getAsJsonObject("goal"));
            runtime.validateGoal(semanticGoal);
        } catch (RuntimeException exception) {
            return requestDecision(RecoveryAdvisor.invalidSemanticAnswer(
                    currentGoal(), safeMessage(exception)));
        }
        if ("recover".equals(answer.choice())) {
            internalStepPositions.remove(record.stepIndex());
            record.insertRecovery(semanticGoal);
            runtime.semanticPlanChanged(record, semanticGoal, false);
        } else {
            internalStepPositions.remove(record.stepIndex());
            record.replaceCurrent(semanticGoal);
            runtime.semanticPlanChanged(record, semanticGoal, true);
        }
        return TaskState.RUNNING;
    }

    private TaskState failStep(TaskState state, TaskResult result) {
        TaskState failureState = state == null ? TaskState.FAILED : state;
        // A failed/abandoned execution can never authorize a later prior_result binding.
        internalStepPositions.remove(record.stepIndex());
        captureMechanicalContinuation(currentGoal(), result);
        TaskResult failure = semanticResult(
                result == null ? TaskResult.fail("internal action failed") : result);
        Goal failedGoal = currentGoal();
        record.addAttempt(new IntentTaskRecord.AttemptSnapshot(
                record.stepIndex(),
                failedGoal,
                failureState,
                failure.message(),
                failure.toJson(),
                player.level().getGameTime()));
        terminalResult = null;
        chain = List.of();
        chainIndex = 0;
        return requestDecision(RecoveryAdvisor.afterFailure(
                failedGoal, failureState, failure));
    }

    private void captureMechanicalContinuation(Goal goal, TaskResult raw) {
        if (goal == null || !"maicraft:connect_mechanical_power".equals(goal.ability())) return;
        String key = continuationKey(goal);
        Object value = raw == null || raw.data() == null
                ? null : raw.data().get("continuation_token");
        if (value == null) {
            mechanicalContinuations.remove(key);
            return;
        }
        try {
            mechanicalContinuations.put(key, UUID.fromString(String.valueOf(value)));
        } catch (IllegalArgumentException invalid) {
            mechanicalContinuations.remove(key);
        }
    }

    private UUID continuationFor(Goal goal) {
        return goal == null ? null : mechanicalContinuations.get(continuationKey(goal));
    }

    private void discardContinuation(Goal goal) {
        if (goal != null) mechanicalContinuations.remove(continuationKey(goal));
    }

    private static String continuationKey(Goal goal) {
        return goal.toJson().toString();
    }

    private TaskState requestDecision(IntentTaskRecord.DecisionSnapshot decision) {
        record.requestDecision(decision, player.level().getGameTime());
        runtime.decision(record, decision);
        return TaskState.RUNNING;
    }

    private TaskState tickWait() {
        if (player.level().getGameTime() < wait.notBeforeGameTime()) return TaskState.RUNNING;
        boolean satisfied = switch (wait.condition()) {
            case "elapsed" -> true;
            case "day" -> player.level().isDay();
            case "night" -> !player.level().isDay();
            case "health_full" -> player.getHealth() >= player.getMaxHealth();
            case "not_hungry" -> player.getFoodData().getFoodLevel() >= 18;
            default -> false;
        };
        if (!satisfied) return TaskState.RUNNING;
        String condition = wait.condition();
        wait = null;
        completeStep(TaskResult.ok("wait condition satisfied: " + condition));
        return afterImmediate();
    }

    private TaskState afterImmediate() {
        if (record.stepIndex() >= record.steps().size()) {
            terminalResult = successResult();
            return TaskState.SUCCESS;
        }
        return TaskState.RUNNING;
    }

    private void completeStep(TaskResult result) {
        result = semanticResult(result);
        int index = record.stepIndex();
        Goal goal = record.steps().get(index);
        record.addStepResult(new IntentTaskRecord.StepSnapshot(
                index, goal.ability(), result.success(), result.message(), result.toJson()));
        if (!result.success()) terminalResult = result;
    }

    private Goal currentGoal() {
        return record.steps().get(record.stepIndex());
    }

    /**
     * Resolve a semantic prior_result to an authoritative position reported by an earlier task.
     * The LLM names the relationship; it never copies coordinates between steps.
     */
    private Goal resolvedCurrentGoal() {
        Goal goal = currentGoal();
        Goal.SemanticTarget target = goal.target();
        if (target == null || !"prior_result".equals(target.kind())) return goal;
        Goal.WorldPosition position = reportedPosition(target);
        return position == null ? goal : goal.withTarget(new Goal.SemanticTarget(
                "coordinates", target.label(), position, target.relation()));
    }

    private Goal.WorldPosition reportedPosition(Goal.SemanticTarget target) {
        List<ReportedPosition> candidates = new ArrayList<>();
        List<IntentTaskRecord.StepSnapshot> completed = record.stepResults();
        for (int index = completed.size() - 1; index >= 0; index--) {
            IntentTaskRecord.StepSnapshot step = completed.get(index);
            if (!step.success()) continue;
            Goal.WorldPosition internal = internalStepPositions.get(step.index());
            if (internal != null) {
                Goal sourceGoal = step.index() >= 0 && step.index() < record.steps().size()
                        ? record.steps().get(step.index()) : null;
                candidates.add(new ReportedPosition(step, sourceGoal, internal));
                continue;
            }
            JsonObject root;
            try {
                root = step.result();
            } catch (RuntimeException ignored) {
                continue;
            }
            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data") : root;
            Goal.WorldPosition position = nestedPosition(data, "verified_position");
            if (position == null) position = nestedPosition(data, "final_position");
            if (position == null) position = nestedPosition(data, "position");
            if (position == null && numbers(data, "final_x", "final_y", "final_z")) {
                position = new Goal.WorldPosition(
                        (int) Math.floor(data.get("final_x").getAsDouble()),
                        (int) Math.floor(data.get("final_y").getAsDouble()),
                        (int) Math.floor(data.get("final_z").getAsDouble()),
                        player.level().dimension().location().toString());
            }
            if (position == null) continue;
            Goal sourceGoal = step.index() >= 0 && step.index() < record.steps().size()
                    ? record.steps().get(step.index()) : null;
            candidates.add(new ReportedPosition(step, sourceGoal, position));
        }
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.getFirst().position();

        String query = ((target.label() == null ? "" : target.label()) + " "
                + (target.relation() == null ? "" : target.relation())).strip();
        int bestScore = 0;
        ReportedPosition best = null;
        boolean ambiguous = false;
        for (ReportedPosition candidate : candidates) {
            int score = semanticScore(query, candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
                ambiguous = false;
            } else if (score > 0 && score == bestScore
                    && best != null && !best.position().equals(candidate.position())) {
                ambiguous = true;
            }
        }
        // Never silently bind "there" to the most recent unrelated task. If more than one
        // authoritative position exists, the semantic relation must distinguish one of them.
        return bestScore > 0 && !ambiguous ? best.position() : null;
    }

    private static int semanticScore(String query, ReportedPosition candidate) {
        if (query == null || query.isBlank()) return 0;
        String needle = normalizeSemanticText(query);
        IntentTaskRecord.StepSnapshot step = candidate.step();
        Goal goal = candidate.goal();
        String source = step.ability() + " " + step.message() + " " + step.resultJson();
        if (goal != null) source += " " + goal.toJson();
        String haystack = normalizeSemanticText(source);
        int score = 0;
        if (!needle.isBlank() && haystack.contains(needle)) score += 200;
        if (goal != null) {
            String outcome = normalizeSemanticText(goal.outcome());
            if (!outcome.isBlank() && (needle.contains(outcome) || outcome.contains(needle))) {
                score += 120;
            }
            String ability = goal.ability();
            int colon = ability.indexOf(':');
            String suffix = normalizeSemanticText(colon < 0 ? ability : ability.substring(colon + 1));
            if (!suffix.isBlank() && needle.contains(suffix)) score += 80;
        }
        for (String unit : semanticUnits(needle)) {
            if (haystack.contains(unit)) score += Math.min(24, 4 + unit.length() * 2);
        }
        return score;
    }

    private static List<String> semanticUnits(String normalized) {
        List<String> result = new ArrayList<>();
        for (String token : normalized.split(" +")) {
            if (token.length() < 2 || List.of(
                    "the", "that", "there", "prior", "previous", "result", "step",
                    "earlier", "place", "position", "from", "into").contains(token)) continue;
            result.add(token);
            int[] points = token.codePoints().toArray();
            if (points.length >= 2 && java.util.Arrays.stream(points).anyMatch(
                    point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN)) {
                for (int index = 0; index + 1 < points.length; index++) {
                    result.add(new String(points, index, 2));
                }
            }
        }
        return result;
    }

    private static String normalizeSemanticText(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}:_./-]+", " ").strip();
    }

    private record ReportedPosition(
            IntentTaskRecord.StepSnapshot step, Goal goal, Goal.WorldPosition position) {}

    private Goal.WorldPosition nestedPosition(JsonObject data, String key) {
        if (!data.has(key) || !data.get(key).isJsonObject()) return null;
        JsonObject value = data.getAsJsonObject(key);
        if (!numbers(value, "x", "y", "z")) return null;
        String dimension = value.has("dimension") && value.get("dimension").isJsonPrimitive()
                ? value.get("dimension").getAsString()
                : player.level().dimension().location().toString();
        return new Goal.WorldPosition(
                (int) Math.floor(value.get("x").getAsDouble()),
                (int) Math.floor(value.get("y").getAsDouble()),
                (int) Math.floor(value.get("z").getAsDouble()), dimension);
    }

    private static boolean numbers(JsonObject value, String x, String y, String z) {
        return value.has(x) && value.get(x).isJsonPrimitive()
                && value.has(y) && value.get(y).isJsonPrimitive()
                && value.has(z) && value.get(z).isJsonPrimitive();
    }

    @Override
    public void stop(LocalPlayer ignored, StopReason reason) {
        if (child == null) return;
        try {
            child.stop(player, reason);
        } catch (RuntimeException ignoredFailure) {
        }
        if (reason != StopReason.PREEMPTED) {
            if (!childRecord.getState().isTerminal()) childRecord.setState(TaskState.CANCELLED);
            try {
                childRecord.setResult(child.result(childRecord.getState()));
            } catch (RuntimeException ignoredFailure) {
            }
            clearChild();
        }
    }

    @Override
    public TaskResult result(TaskState terminal) {
        if (child != null) {
            stop(player, StopReason.REPLACED);
        }
        TaskResult result = terminalResult;
        if (result == null) {
            result = switch (terminal) {
                case SUCCESS -> successResult();
                case TIMEOUT -> TaskResult.timeout("semantic task timed out");
                case CANCELLED -> TaskResult.cancelled("semantic task cancelled");
                default -> TaskResult.fail("semantic task failed");
            };
        }
        if (!terminalPublished) {
            terminalPublished = true;
            record.terminal(terminal, result, player.level().getGameTime());
            runtime.terminal(record, terminal, result);
        }
        return result;
    }

    private TaskResult successResult() {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (IntentTaskRecord.StepSnapshot step : record.stepResults()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", step.index());
            item.put("ability", step.ability());
            item.put("success", step.success());
            item.put("message", step.message());
            steps.add(item);
        }
        return TaskResult.ok(record.goal().outcome(),
                Map.of("task_id", record.externalId().toString(), "steps", steps));
    }

    private static TaskResult parseImmediate(String json) {
        try {
            JsonObject value = JsonParser.parseString(json).getAsJsonObject();
            boolean success = value.has("success") && value.get("success").getAsBoolean();
            String message = value.has("message") ? value.get("message").getAsString() : "";
            Map<String, Object> data = new LinkedHashMap<>();
            if (value.has("data") && value.get("data").isJsonObject()) {
                data.put("tool_data", value.getAsJsonObject("data").toString());
            }
            return success ? TaskResult.ok(message, data) : TaskResult.fail(message, data);
        } catch (RuntimeException exception) {
            return TaskResult.fail("internal tool returned invalid JSON: " + safeMessage(exception));
        }
    }

    private static TaskResult defaultResult(TaskState state) {
        return switch (state) {
            case SUCCESS -> TaskResult.ok("child completed");
            case TIMEOUT -> TaskResult.timeout("child timed out");
            case CANCELLED -> TaskResult.cancelled("child cancelled");
            default -> TaskResult.fail("child failed");
        };
    }

    private void clearChild() {
        child = null;
        childRecord = null;
    }

    private void abandonChildAfterUnexpectedFailure() {
        Task failed = child;
        clearChild();
        if (failed == null) return;
        try {
            ClientRuntime.requireContext(player).body().releaseAll();
        } catch (RuntimeException ignoredFailure) {
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    /**
     * Internal tasks may use runtime entity ids, menu slots and concrete routes to keep native
     * work stable across ticks. Those implementation details stop here: the public semantic task
     * reports outcomes and evidence, never handles that the model could replay as micro-actions.
     */
    private static TaskResult semanticResult(TaskResult raw) {
        if (raw == null) return TaskResult.fail("internal action failed");
        return new TaskResult(
                raw.success(),
                sanitizeMessage(raw.message()),
                raw.timedOut(),
                raw.interrupted(),
                sanitizeMap(raw.data()));
    }

    private static Map<String, Object> sanitizeMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key == null || internalResultKey(key)) continue;
            Object value = sanitizeValue(entry.getValue());
            if (value != null) clean.put(key, value);
        }
        return Map.copyOf(clean);
    }

    private static Object sanitizeValue(Object value) {
        if (value instanceof com.google.gson.JsonElement json) {
            return sanitizeJson(json);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> clean = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (internalResultKey(key)) continue;
                Object nested = sanitizeValue(entry.getValue());
                if (nested != null) clean.put(key, nested);
            }
            return Map.copyOf(clean);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> clean = new ArrayList<>();
            for (Object element : collection) {
                Object nested = sanitizeValue(element);
                if (nested != null) clean.add(nested);
            }
            return List.copyOf(clean);
        }
        if (value instanceof String text) {
            String stripped = text.strip();
            if ((stripped.startsWith("{") && stripped.endsWith("}"))
                    || (stripped.startsWith("[") && stripped.endsWith("]"))) {
                try {
                    return sanitizeJson(JsonParser.parseString(stripped));
                } catch (RuntimeException ignored) {
                    // Ordinary text that merely resembles JSON remains ordinary text.
                }
            }
            return sanitizeMessage(text);
        }
        return value;
    }

    private static Object sanitizeJson(com.google.gson.JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonObject()) {
            Map<String, Object> clean = new LinkedHashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> entry
                    : value.getAsJsonObject().entrySet()) {
                if (internalResultKey(entry.getKey())) continue;
                Object nested = sanitizeJson(entry.getValue());
                if (nested != null) clean.put(entry.getKey(), nested);
            }
            return Map.copyOf(clean);
        }
        if (value.isJsonArray()) {
            List<Object> clean = new ArrayList<>();
            for (com.google.gson.JsonElement element : value.getAsJsonArray()) {
                Object nested = sanitizeJson(element);
                if (nested != null) clean.add(nested);
            }
            return List.copyOf(clean);
        }
        var primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isNumber()) return primitive.getAsNumber();
        return sanitizeMessage(primitive.getAsString());
    }

    private static boolean internalResultKey(String raw) {
        String key = raw.toLowerCase(Locale.ROOT);
        return INTERNAL_RESULT_KEYS.contains(key)
                || key.endsWith("_cells")
                || key.endsWith("_ops")
                || key.endsWith("_placements")
                || key.endsWith("_receipts")
                || key.endsWith("_routes")
                || key.endsWith("_waypoints")
                || key.endsWith("_path_nodes")
                || key.endsWith("_position")
                || key.endsWith("_center")
                || key.endsWith("_location")
                || key.endsWith("_destination")
                || key.endsWith("_bounds")
                || key.endsWith("_x")
                || key.endsWith("_y")
                || key.endsWith("_z")
                || key.endsWith("_entity_id")
                || key.endsWith("_entity_ids")
                || key.endsWith("_runtime_id")
                || key.endsWith("_runtime_ids");
    }

    private static String sanitizeMessage(String raw) {
        if (raw == null) return "";
        return raw
                .replaceAll("(?i)entity\\s*#?\\s*\\d+", "selected entity")
                .replaceAll("(?i)runtime\\s+id\\s*[:=]?\\s*\\d+", "internal target")
                .replaceAll(
                        "(?i)(?:location\\s+)?x\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?\\s+z\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?(?:,?\\s*standing\\s+on\\s+the\\s+ground\\s+at\\s+y\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?)?",
                        "the internally verified location")
                .replaceAll("(?<!\\d)-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+(?!\\d)",
                        "the internally verified location")
                .replaceAll("(?i)\\b[xyz]\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?",
                        "the internally verified coordinate");
    }

    @Override
    public String name() {
        return "intent";
    }
}
