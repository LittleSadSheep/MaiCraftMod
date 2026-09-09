package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.task.InternalAreaProtectionReceipt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** MCP 总任务的任务单：记住总目标、做到了哪一步、为什么暂停，以及正在等调用者回答什么。 */
public final class IntentTaskRecord extends TaskRecord {

    private static final int MAX_ATTEMPTS = 64;
    private static final com.google.gson.Gson PROGRESS_JSON = new com.google.gson.Gson();

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
    /** Opaque measured area protections; public task snapshots deliberately omit them. */
    private final LinkedHashMap<Integer, List<InternalAreaProtectionReceipt.Footprint>>
            internalAreaProtections = new LinkedHashMap<>();

    private int stepIndex;
    private PauseSnapshot pause;
    private DecisionSnapshot decision;
    private DecisionAnswer pendingAnswer;
    private TerminalSnapshot terminal;
    private boolean restoredDetached;
    private Runnable dirty = () -> {};
    /** Live diagnostics only; neither task/world references nor stale restored progress are retained. */
    private JsonObject activeExecution;

    public IntentTaskRecord(UUID externalId, UUID planId, Goal goal) {
        this(externalId, planId, goal, null);
    }

    IntentTaskRecord(UUID externalId, UUID planId, Goal goal, String bindingKey) {
        super("execute", "mcp-" + externalId, NO_DEADLINE);
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
            Map<Integer, List<InternalAreaProtectionReceipt.Footprint>> internalAreaProtections,
            List<AttemptSnapshot> attempts,
            DecisionSnapshot decision,
            DecisionAnswer pendingAnswer,
            TerminalSnapshot terminal,
            long restoredGameTime) {
        // 只恢复目标和已经确认的结果，不恢复旧路线或菜单操作；没做完的任务先暂停，等明确要求继续。
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
        for (Map.Entry<Integer, List<InternalAreaProtectionReceipt.Footprint>> entry
                : internalAreaProtections.entrySet()) {
            int index = entry.getKey();
            if (index >= 0 && index < stepIndex && entry.getValue() != null
                    && !entry.getValue().isEmpty()) {
                record.internalAreaProtections.put(index, List.copyOf(entry.getValue()));
            }
        }
        record.attempts.addAll(attempts.stream().skip(
                Math.max(0, attempts.size() - MAX_ATTEMPTS)).toList());
        // Older checkpoints could retain a pending decision beside a terminal receipt.
        record.decision = terminal == null ? decision : null;
        record.pendingAnswer = terminal == null ? pendingAnswer : null;
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
    /** Persistence-only snapshot; concrete cells never enter the MCP facade. */
    public Map<Integer, List<InternalAreaProtectionReceipt.Footprint>>
            internalAreaProtectionReceipts() {
        return Map.copyOf(internalAreaProtections);
    }
    public PauseSnapshot pauseSnapshot() { return pause; }
    public DecisionSnapshot decisionSnapshot() { return decision; }
    public TerminalSnapshot terminalSnapshot() { return terminal; }
    public boolean paused() { return pause != null; }
    public String bindingKey() { return bindingKey; }
    public boolean restoredDetached() { return restoredDetached; }

    public JsonObject activeExecution() {
        return activeExecution == null ? null : activeExecution.deepCopy();
    }

    void observeExecution(Map<String, Object> progress, long gameTime) {
        // 复制最近一次看到的执行进度，查询者读到的是这次观察的内容，不持有会继续变化的任务对象。
        activeExecution = PROGRESS_JSON.toJsonTree(progress).getAsJsonObject();
        activeExecution.addProperty("observed_game_time", gameTime);
    }

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
        // 先记下这一步的结果，再把“当前步骤”往后挪一格，并通知保存功能有新内容。
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

    void retainInternalAreaProtections(
            int index, List<InternalAreaProtectionReceipt.Footprint> protections) {
        if (index < 0 || index >= steps.size() || protections == null) {
            throw new IllegalArgumentException("invalid internal semantic area receipt");
        }
        List<InternalAreaProtectionReceipt.Footprint> clean = protections.stream()
                .filter(Objects::nonNull).toList();
        if (clean.isEmpty()) return;
        internalAreaProtections.put(index, List.copyOf(clean));
        changed();
    }

    void addAttempt(AttemptSnapshot snapshot) {
        // 同一步可以失败多次，只保留最近六十四次尝试，避免历史越积越大。
        attempts.add(snapshot);
        while (attempts.size() > MAX_ATTEMPTS) attempts.remove(0);
        changed();
    }

    void insertRecovery(Goal recovery) {
        // 例如造炉子缺石头：把“找石头”插在“造炉子”前面，找齐后还会回到造炉子这一步。
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
        // 调用者改变主意：替换还没做成的这一步，已完成的步骤和后续步骤保留。
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

    /** Freeze the physical design once compilation has produced an executable project. */
    void retainBuildProject(String id) {
        retainBuildProject(id, List.of());
    }

    boolean retainBuildProject(String id, List<String> savedProtectionLabels) {
        if (id == null || stepIndex >= steps.size()) return false;
        Goal current = steps.get(stepIndex);
        if (!"maicraft:build".equals(current.ability())) return false;
        JsonObject parameters = new JsonObject();
        parameters.addProperty("project_id", id);
        var labels = new java.util.LinkedHashSet<>(savedProtectionLabels);
        if (current.parameters().has("protected_labels")) current.parameters().getAsJsonArray("protected_labels")
                .forEach(value -> labels.add(value.getAsString()));
        if (!labels.isEmpty()) {
            var values = new com.google.gson.JsonArray();
            labels.forEach(values::add);
            parameters.add("protected_labels", values);
        }
        if (current.parameters().equals(parameters)) return false;
        steps.set(stepIndex, current.withParameters(parameters));
        changed();
        return true;
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
        // 这里只记“暂停”和原因，不直接松按键；调度器之后根据这个标记决定是否继续调用执行代码。
        if (getState().isTerminal()) return false;
        if (pause == null) {
            pause = new PauseSnapshot(reason, gameTime, decision == null ? null : decision.id());
            changed();
        }
        return true;
    }

    public boolean resume() {
        // 还有问题没回答时不能直接继续，必须先 answer；已经结束的任务也不能当作暂停任务恢复。
        if (getState().isTerminal() || decision != null) return false;
        pause = null;
        changed();
        return true;
    }

    void requestDecision(DecisionSnapshot next, long gameTime) {
        // 有新问题时，旧答复作废，并暂停任务，等这一个问题得到有效回答。
        decision = Objects.requireNonNull(next, "decision");
        pendingAnswer = null;
        pause = new PauseSnapshot("waiting_for_decision", gameTime, next.id());
        changed();
    }

    public boolean answer(UUID decisionId, String choice, JsonObject details) {
        // 问题编号和选项都必须匹配，防止迟到的答复被用到另一个问题上；接受后下一次执行再处理。
        if (getState().isTerminal() || terminal != null) return false;
        if (decision == null || !decision.id().equals(decisionId) || !decision.accepts(choice)) return false;
        pendingAnswer = new DecisionAnswer(decisionId, choice,
                details == null ? "{}" : details.toString());
        decision = null;
        pause = null;
        changed();
        return true;
    }

    DecisionAnswer takeAnswer() {
        // 答复只取一次，避免每个游戏刻都重复执行同一个“重试”决定。
        DecisionAnswer answer = pendingAnswer;
        pendingAnswer = null;
        if (answer != null) changed();
        return answer;
    }

    void terminal(TaskState state, TaskResult result, long gameTime) {
        // 最后结果确定后，清掉暂停、待答问题和未处理答复；结束的任务不再等待人回答。
        if (!state.isTerminal()) throw new IllegalArgumentException("terminal receipt requires a terminal state");
        terminal = new TerminalSnapshot(state, result == null ? "{}" : result.toJson(), gameTime);
        setState(state);
        pause = null;
        decision = null;
        pendingAnswer = null;
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
