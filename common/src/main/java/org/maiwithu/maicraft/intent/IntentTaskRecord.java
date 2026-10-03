package org.maiwithu.maicraft.intent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.maiwithu.maicraft.task.InternalAreaProtectionReceipt;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.core.task.build.BuildPreviewGate;
import org.maiwithu.maicraft.core.task.container.ContainerSearchScope;

/** MCP 总任务的任务单：记住总目标、做到了哪一步、为什么暂停，以及正在等调用者回答什么。 */
public final class IntentTaskRecord extends TaskRecord {

    private static final int MAX_ATTEMPTS = 64;
    private static final Gson PROGRESS_JSON = new Gson();

    private final UUID externalId;
    private final UUID planId;
    private final Goal goal;
    private final List<Goal> steps;
    private final String bindingKey;
    private final List<StepSnapshot> stepResults = new ArrayList<>();
    private final List<AttemptSnapshot> attempts = new ArrayList<>();
    /** 已确认位置仅供后续语义目标引用，不把这些内部坐标放进公开任务结果。 */
    private final LinkedHashMap<Integer, Goal.WorldPosition> internalStepPositions =
            new LinkedHashMap<>();
    /** 实测保护范围留给后续步骤和检查点使用，公开任务快照不输出具体方块格。 */
    private final LinkedHashMap<Integer, List<InternalAreaProtectionReceipt.Footprint>>
            internalAreaProtections = new LinkedHashMap<>();
    /** 每个取物步骤的首次翻箱范围；空值表示旧检查点未记录，不能在恢复地点重新捕获。 */
    private final Map<Integer, Optional<ContainerSearchScope>> containerSearchScopes = new LinkedHashMap<>();

    private int stepIndex;
    private PauseSnapshot pause;
    private DecisionSnapshot decision;
    private DecisionAnswer pendingAnswer;
    private TerminalSnapshot terminal;
    private boolean restoredDetached;
    private boolean chatSubmissionTracked = true;
    private Runnable dirty = () -> {};
    /** 只保存最近一次观察的诊断副本；不持有世界对象，也不把旧进度当成重启后的现场事实。 */
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
        // 旧检查点不能仅因被新版本读取就升级成有持久发送证据；运行时随后带回文件中的明确标记。
        record.chatSubmissionTracked = false;
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
        // 旧检查点可能同时留下终态和待答问题；已经结束的任务不能再被旧问题恢复。
        record.decision = terminal == null ? decision : null;
        record.pendingAnswer = terminal == null ? pendingAnswer : null;
        record.terminal = terminal;
        if (terminal == null) {
            // 老记录没有运行中子任务的范围证据；随后只有检查点明确保存的范围才能覆盖这个未知标记。
            if (stepIndex < steps.size() && AcquireAbilityAdapter.ABILITY.equals(steps.get(stepIndex).ability()))
                record.containerSearchScopes.put(stepIndex, Optional.empty());
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
    /** 改目标必须经过任务单的方法，使保护范围和保存通知一起更新；查询者不能直接改步骤表。 */
    public List<Goal> steps() { return Collections.unmodifiableList(steps); }
    public int stepIndex() { return stepIndex; }
    public List<StepSnapshot> stepResults() { return List.copyOf(stepResults); }
    /** 用户明确略过的目标单独计数，不能混进游戏中已经完成的效果。 */
    public int skippedStepCount() {
        return (int) stepResults.stream().filter(StepSnapshot::skipped).count();
    }
    /** 当前有效清单每一步都须留下成功证据；恢复或替换后的清单不能自动证明最初请求的游戏产物。 */
    public boolean allStepsSucceeded() {
        // 零步骤记录（如死亡恢复承载单）不是"全部成功"；真正的任务单至少有一个可执行步骤。
        return stepIndex == steps.size() && !steps.isEmpty() && stepResults.size() == steps.size()
                && stepResults.stream().allMatch(StepSnapshot::success);
    }
    public List<AttemptSnapshot> attempts() { return List.copyOf(attempts); }
    public Map<Integer, Optional<ContainerSearchScope>> containerSearchScopes() { return Map.copyOf(containerSearchScopes); }

    void restoreContainerSearchScopes(Map<Integer, Optional<ContainerSearchScope>> saved) {
        // 只让检查点中的明确记录覆盖默认未知，不根据恢复后的站位补写任何坐标。
        containerSearchScopes.putAll(saved);
    }

    Optional<ContainerSearchScope> retainContainerSearchScope(ContainerSearchScope initial) {
        // 同一语义步骤的重试只复用首次范围；旧记录的未知状态也属于已保存的事实。
        if (!containerSearchScopes.containsKey(stepIndex)) {
            containerSearchScopes.put(stepIndex, Optional.of(Objects.requireNonNull(initial))); changed();
        }
        return containerSearchScopes.get(stepIndex);
    }
    /** 供检查点保存内部位置，MCP 查询不序列化这份坐标表。 */
    public Map<Integer, Goal.WorldPosition> internalPositionReceipts() {
        return Map.copyOf(internalStepPositions);
    }
    /** 供检查点保存保护范围，MCP 查询不输出这里的具体方块格。 */
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
    public boolean chatSubmissionTracked() { return chatSubmissionTracked; }

    void restoreChatSubmissionTracking(boolean tracked) {
        if (!restoredDetached && terminal == null) throw new IllegalStateException("chat tracking can only be restored with a checkpoint");
        chatSubmissionTracked = tracked;
    }

    public JsonObject activeExecution() {
        // 人工审图时角色调度已经停刻，查询仍必须即时看到审核门禁，不能只读停刻前最后一帧 survey。
        Map<String, Object> review = BuildPreviewGate.waitingProgress(this);
        if (activeExecution == null && review.isEmpty()) return null;
        JsonObject current = activeExecution == null ? new JsonObject() : activeExecution.deepCopy();
        PROGRESS_JSON.toJsonTree(review).getAsJsonObject().entrySet().forEach(entry -> current.add(entry.getKey(), entry.getValue()));
        return current;
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
        // 前置工作仍属于当前分组，不能借插入恢复步骤绕过祖先的保护要求。
        Goal scoped = stepIndex < steps.size()
                ? recovery.withInheritedProtection(steps.get(stepIndex).inheritedProtectionLabels()) : recovery;
        List<Goal> expanded = expandedSteps(scoped, "recovery");
        // 一次把全部前置步骤插在失败步骤之前；原步骤紧随其后，等整组前置工作完成再重试。
        List<Goal> updated = new ArrayList<>(steps.size() + expanded.size());
        updated.addAll(steps.subList(0, stepIndex));
        updated.addAll(expanded);
        updated.addAll(steps.subList(stepIndex, steps.size()));
        steps.clear();
        steps.addAll(updated);
        shiftContainerSearchScopes(0, expanded.size());
        changed();
    }

    boolean updateCurrentParameters(JsonObject parameters) {
        // 重试的真实预算、配方等先写回当前步骤；保留地标或prior_result原意，临时解析的坐标不能成为新持久目标。
        Goal current = steps.get(stepIndex);
        if (current.parameters().equals(parameters)) return false;
        steps.set(stepIndex, current.withParameters(parameters));
        changed();
        return true;
    }

    void replaceCurrent(Goal replacement) {
        // 调用者改变主意：替换还没做成的这一步，已完成的步骤和后续步骤保留。
        if (stepIndex >= steps.size()) {
            throw new IllegalStateException("there is no current semantic step to replace");
        }
        List<Goal> expanded = expandedSteps(replacement.withInheritedProtection(
                steps.get(stepIndex).inheritedProtectionLabels()), "replacement");
        List<Goal> updated = new ArrayList<>(steps.size() - 1 + expanded.size());
        updated.addAll(steps.subList(0, stepIndex));
        updated.addAll(expanded);
        updated.addAll(steps.subList(stepIndex + 1, steps.size()));
        steps.clear();
        steps.addAll(updated);
        shiftContainerSearchScopes(1, expanded.size());
        changed();
    }

    private void shiftContainerSearchScopes(int removed, int inserted) {
        // 插入备料步骤时原目标只是后移，仍保留旧范围；明确替换目标才丢弃被替换步骤的范围。
        var shifted = new LinkedHashMap<Integer, Optional<ContainerSearchScope>>();
        containerSearchScopes.forEach((index, scope) -> {
            if (index < stepIndex) shifted.put(index, scope);
            else if (index >= stepIndex + removed) shifted.put(index + inserted - removed, scope);
        });
        containerSearchScopes.clear(); containerSearchScopes.putAll(shifted);
    }

    /** 建筑编译成可执行工程后保留冻结设计，恢复时沿用原来的几何与材料。 */
    void retainBuildProject(String id) {
        retainBuildProject(id, List.of());
    }

    boolean retainBuildProject(String id, List<String> savedProtectionLabels) {
        if (id == null || stepIndex >= steps.size()) return false;
        Goal current = steps.get(stepIndex);
        if (!"maicraft:build".equals(current.ability())) return false;
        JsonObject parameters = new JsonObject();
        parameters.addProperty("project_id", id);
        var labels = new LinkedHashSet<>(savedProtectionLabels);
        if (current.parameters().has("protected_labels")) current.parameters().getAsJsonArray("protected_labels")
                .forEach(value -> labels.add(value.getAsString()));
        if (!labels.isEmpty()) {
            var values = new JsonArray();
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
        // 终态任务通常不再匹配答复，防止迟到的答复被用到另一份决策上。
        // 唯独死亡恢复决策例外：它的主体是死亡本身，body_gone 抢先终结任务后问题依然成立，
        // 必须放行答复（Facade 会同步应用重生/观战/取消），否则没有任何 MCP 入口能完成重生。
        boolean terminalBlock = (getState().isTerminal() || terminal != null) && !deathRecovery(decision);
        if (terminalBlock) return false;
        if (decision == null || !decision.id().equals(decisionId) || !decision.accepts(choice)) return false;
        pendingAnswer = new DecisionAnswer(decisionId, choice,
                details == null ? "{}" : details.toString());
        decision = null;
        pause = null;
        changed();
        return true;
    }

    private boolean deathRecoveryPending(UUID decisionId) {
        return decision != null && decisionId.equals(decision.id())
                && decision.contextJson() != null
                && decision.contextJson().contains("death_recovery");
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
        // 例外是死亡恢复决策：它的主体是死亡本身，body_gone 抢先终结任务后
        // "重生还是取消"依然必须有人能回答，清掉它等于封死唯一的重生入口。
        if (!state.isTerminal()) throw new IllegalArgumentException("terminal receipt requires a terminal state");
        terminal = new TerminalSnapshot(state, result == null ? "{}" : result.toJson(), gameTime);
        setState(state);
        pause = null;
        if (!deathRecovery(decision)) decision = null;
        pendingAnswer = null;
        changed();
    }

    static boolean deathRecovery(DecisionSnapshot snapshot) {
        return snapshot != null && snapshot.contextJson() != null
                && snapshot.contextJson().contains("death_recovery");
    }

    /** 死亡已由其他途径解决（如人工点击重生）；遗留的恢复问题作废，终态记录不再接受迟到答复。 */
    void clearPendingDecision() {
        decision = null;
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
                               String message, String resultJson, boolean skipped) {
        public StepSnapshot(int index, String ability, boolean success, String message, String resultJson) {
            this(index, ability, success, message, resultJson, false);
        }

        public StepSnapshot {
            // 跳过表示用户不再要求执行这一步，绝不能同时声称这一步已经在游戏里成功。
            success = success && !skipped;
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
