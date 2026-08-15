package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** TaskRecord used by the public semantic-intent surface. */
public final class IntentTaskRecord extends TaskRecord {

    private static final int MAX_ATTEMPTS = 64;

    private final UUID externalId;
    private final UUID planId;
    private final Goal goal;
    private final List<Goal> steps;
    private final String bindingKey;
    private final List<StepSnapshot> stepResults = new ArrayList<>();
    private final List<AttemptSnapshot> attempts = new ArrayList<>();
    /** Opaque verified positions used only for semantic prior_result binding. */
    private final LinkedHashMap<Integer, Goal.WorldPosition> internalStepPositions =
            new LinkedHashMap<>();

    private int stepIndex;
    private PauseSnapshot pause;
    private DecisionSnapshot decision;
    private DecisionAnswer pendingAnswer;
    private TerminalSnapshot terminal;
    private boolean restoredDetached;
    private Runnable dirty = () -> {};

    public IntentTaskRecord(UUID externalId, UUID planId, Goal goal) {
        this(externalId, planId, goal, null);
    }

    IntentTaskRecord(UUID externalId, UUID planId, Goal goal, String bindingKey) {
        super("maicraft_execute", "mcp-" + externalId, NO_DEADLINE);
        this.externalId = Objects.requireNonNull(externalId, "externalId");
        this.planId = planId;
        this.goal = Objects.requireNonNull(goal, "goal");
        this.steps = new ArrayList<>(goal.executableSteps());
        this.bindingKey = bindingKey;
        markAsync();
    }

    static IntentTaskRecord restored(
            UUID externalId,
            UUID planId,
            Goal goal,
            String bindingKey,
            List<Goal> steps,
            int stepIndex,
            List<StepSnapshot> stepResults,
            Map<Integer, Goal.WorldPosition> internalStepPositions,
            List<AttemptSnapshot> attempts,
            DecisionSnapshot decision,
            DecisionAnswer pendingAnswer,
            TerminalSnapshot terminal,
            long restoredGameTime) {
        IntentTaskRecord record = new IntentTaskRecord(
                externalId, planId, goal, Objects.requireNonNull(bindingKey, "bindingKey"));
        record.steps.clear();
        record.steps.addAll(steps);
        record.stepIndex = stepIndex;
        record.stepResults.addAll(stepResults);
        for (Map.Entry<Integer, Goal.WorldPosition> entry
                : internalStepPositions.entrySet()) {
            int index = entry.getKey();
            if (index >= 0 && index < stepIndex && entry.getValue() != null) {
                record.internalStepPositions.put(index, entry.getValue());
            }
        }
        record.attempts.addAll(attempts.stream().skip(
                Math.max(0, attempts.size() - MAX_ATTEMPTS)).toList());
        record.decision = decision;
        record.pendingAnswer = pendingAnswer;
        record.terminal = terminal;
        if (terminal == null) {
            record.pause = new PauseSnapshot(
                    "paused_restored", restoredGameTime,
                    decision == null ? null : decision.id());
            record.restoredDetached = true;
            record.setState(TaskState.PENDING);
        } else {
            record.setState(terminal.state());
        }
        return record;
    }

    public UUID externalId() { return externalId; }
    public UUID planId() { return planId; }
    public Goal goal() { return goal; }
    public List<Goal> steps() { return steps; }
    public int stepIndex() { return stepIndex; }
    public List<StepSnapshot> stepResults() { return List.copyOf(stepResults); }
    public List<AttemptSnapshot> attempts() { return List.copyOf(attempts); }
    /** Persistence-only snapshot; the MCP facade deliberately never serializes this map. */
    public Map<Integer, Goal.WorldPosition> internalPositionReceipts() {
        return Map.copyOf(internalStepPositions);
    }
    public PauseSnapshot pauseSnapshot() { return pause; }
    public DecisionSnapshot decisionSnapshot() { return decision; }
    public TerminalSnapshot terminalSnapshot() { return terminal; }
    public boolean paused() { return pause != null; }
    public String bindingKey() { return bindingKey; }
    public boolean restoredDetached() { return restoredDetached; }

    void bindDirty(Runnable dirty) {
        this.dirty = dirty == null ? () -> {} : dirty;
    }

    void markAttached() {
        if (!restoredDetached) return;
        restoredDetached = false;
        changed();
    }

    public DecisionAnswer pendingAnswerSnapshot() {
        return pendingAnswer;
    }

    void addStepResult(StepSnapshot snapshot) {
        stepResults.add(snapshot);
        stepIndex++;
        changed();
    }

    Goal.WorldPosition internalStepPosition(int index) {
        return internalStepPositions.get(index);
    }

    void retainInternalStepPosition(int index, Goal.WorldPosition position) {
        if (index < 0 || index >= steps.size() || position == null) {
            throw new IllegalArgumentException("invalid internal semantic position receipt");
        }
        internalStepPositions.put(index, position);
        changed();
    }

    void discardInternalStepPosition(int index) {
        if (internalStepPositions.remove(index) != null) changed();
    }

    void addAttempt(AttemptSnapshot snapshot) {
        attempts.add(snapshot);
        while (attempts.size() > MAX_ATTEMPTS) attempts.remove(0);
        changed();
    }

    void insertRecovery(Goal recovery) {
        List<Goal> expanded = expandedSteps(recovery, "recovery");
        // Insert every prerequisite before the failed step in one semantic-plan mutation. The
        // original step stays immediately after the expansion, so it is retried only after the
        // complete recovery sequence succeeds.
        List<Goal> updated = new ArrayList<>(steps.size() + expanded.size());
        updated.addAll(steps.subList(0, stepIndex));
        updated.addAll(expanded);
        updated.addAll(steps.subList(stepIndex, steps.size()));
        steps.clear();
        steps.addAll(updated);
        changed();
    }

    void replaceCurrent(Goal replacement) {
        if (stepIndex >= steps.size()) {
            throw new IllegalStateException("there is no current semantic step to replace");
        }
        List<Goal> expanded = expandedSteps(replacement, "replacement");
        List<Goal> updated = new ArrayList<>(steps.size() - 1 + expanded.size());
        updated.addAll(steps.subList(0, stepIndex));
        updated.addAll(expanded);
        updated.addAll(steps.subList(stepIndex + 1, steps.size()));
        steps.clear();
        steps.addAll(updated);
        changed();
    }

    private static List<Goal> expandedSteps(Goal goal, String label) {
        List<Goal> expanded = List.copyOf(
                Objects.requireNonNull(goal, label).executableSteps());
        if (expanded.isEmpty()) {
            throw new IllegalArgumentException(label + " must contain at least one executable step");
        }
        return expanded;
    }

    public boolean pause(long gameTime, String reason) {
        if (getState().isTerminal()) return false;
        if (pause == null) {
            pause = new PauseSnapshot(reason, gameTime, decision == null ? null : decision.id());
            changed();
        }
        return true;
    }

    public boolean resume() {
        if (getState().isTerminal() || decision != null) return false;
        pause = null;
        changed();
        return true;
    }

    void requestDecision(DecisionSnapshot next, long gameTime) {
        decision = Objects.requireNonNull(next, "decision");
        pendingAnswer = null;
        pause = new PauseSnapshot("waiting_for_decision", gameTime, next.id());
        changed();
    }

    public boolean answer(UUID decisionId, String choice, JsonObject details) {
        if (decision == null || !decision.id().equals(decisionId) || !decision.accepts(choice)) return false;
        pendingAnswer = new DecisionAnswer(decisionId, choice,
                details == null ? "{}" : details.toString());
        decision = null;
        pause = null;
        changed();
        return true;
    }

    DecisionAnswer takeAnswer() {
        DecisionAnswer answer = pendingAnswer;
        pendingAnswer = null;
        if (answer != null) changed();
        return answer;
    }

    void terminal(TaskState state, TaskResult result, long gameTime) {
        terminal = new TerminalSnapshot(state, result == null ? "{}" : result.toJson(), gameTime);
        changed();
    }

    private void changed() {
        dirty.run();
    }

    @Override
    public String describe() {
        if (terminal != null) return goal.outcome() + " (" + terminal.state().name().toLowerCase() + ")";
        if (decision != null) return goal.outcome() + " (decision needed)";
        if (pause != null) return goal.outcome() + " (paused)";
        return goal.outcome() + " (step " + Math.min(stepIndex + 1, Math.max(1, steps.size()))
                + "/" + Math.max(1, steps.size()) + ")";
    }

    public record StepSnapshot(int index, String ability, boolean success,
                               String message, String resultJson) {
        public StepSnapshot {
            resultJson = resultJson == null ? "{}" : resultJson;
        }

        public JsonObject result() {
            return JsonParser.parseString(resultJson).getAsJsonObject();
        }
    }

    public record AttemptSnapshot(int stepIndex, Goal goal, TaskState state,
                                  String message, String resultJson, long gameTime) {
        public AttemptSnapshot {
            resultJson = resultJson == null ? "{}" : resultJson;
        }

        public JsonObject result() {
            return JsonParser.parseString(resultJson).getAsJsonObject();
        }
    }

    public record PauseSnapshot(String reason, long gameTime, UUID decisionId) {}

    public record DecisionOption(String choice, String description) {}

    public record DecisionSnapshot(UUID id, String question, List<DecisionOption> options,
                                   String contextJson) {
        public DecisionSnapshot {
            id = Objects.requireNonNull(id, "id");
            options = List.copyOf(options);
            contextJson = contextJson == null ? "{}" : contextJson;
        }

        boolean accepts(String choice) {
            return options.stream().anyMatch(option -> option.choice().equals(choice));
        }

        public JsonObject context() {
            return JsonParser.parseString(contextJson).getAsJsonObject();
        }
    }

    public record DecisionAnswer(UUID decisionId, String choice, String detailsJson) {
        public JsonObject details() {
            return JsonParser.parseString(detailsJson).getAsJsonObject();
        }
    }

    public record TerminalSnapshot(TaskState state, String resultJson, long gameTime) {
        public JsonObject result() {
            return JsonParser.parseString(resultJson).getAsJsonObject();
        }
    }
}
