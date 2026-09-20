package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.ToolRegistry;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.integration.create.CreateMechanicalPower;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.base.NativeSubmissionTaskRecord;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticBuildSupplyTaskRecord;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.InternalAreaProtectionReceipt;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskDispatch;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 把“我想做成这件事”一步步做出来，例如先取材料，再建房子。
 * 这个类记住当前做哪一步，并调用负责走路、挖矿等具体动作的任务；遇到做不下去的情况就询问调用者。
 * 调度器只看到这个总任务，里面的小任务由这里推进，不会把总任务挤走。
 */
final class IntentTask implements Task {

    private final LocalPlayer player;
    private final IntentTaskRecord record;
    private final IntentRuntime runtime;

    private Task child;
    private TaskRecord childRecord;
    private boolean reobserveAfterChild;
    private IntentAction.Wait wait;
    private List<IntentAction.Tool> chain = List.of();
    private int chainIndex;
    private TaskResult terminalResult;
    private TaskResult interruptedChildResult;
    private boolean terminalPublished;
    private long childSerial;
    /** 单次机械续建凭据只保留在 Mod 内部，调用方仍通过语义重试选择下一步。 */
    private final Map<String, UUID> mechanicalContinuations = new LinkedHashMap<>();
    private String cachedProtectionDimension;
    private int cachedProtectionStep = -1;
    private LongSet cachedProtectedMutationCells = LongSets.emptySet();
    private LongSet cachedForbiddenBodyCells = LongSets.emptySet();
    IntentTask(LocalPlayer player, IntentTaskRecord record, IntentRuntime runtime) {
        this.player = player;
        this.record = record;
        this.runtime = runtime;
    }

    @Override
    public boolean canRun(LocalPlayer ignored) {
        // 总任务被暂停时不主动争取执行机会；能否马上停下，还要由外层判断当前动作是否安全。
        return !record.paused();
    }

    @Override
    public TaskState tick(LocalPlayer ignored) {
        try {
            return tickSemanticParent();
        } catch (RuntimeException failure) {
            // 适配器结果异常或内部能力出错时，仍记录失败并进入语义恢复流程。
            // 这里只处理运行异常；虚拟机级错误不被包装成普通任务失败。
            abandonChildAfterUnexpectedFailure();
            wait = null;
            // 无持久聊天记录的旧任务只能报告未知；不能把“新会话尚未发送”误当成普通可重试失败。
            Map<String, Object> evidence = failure instanceof NativeSubmissionBinding.UntrackedChatHistory
                    ? Map.of("failure_code", "chat_history_untracked", "outcome_uncertain", true, "mechanical_retry_allowed", false)
                    : Map.of();
            return failStep(TaskState.FAILED,
                    TaskResult.fail("semantic step failed safely: " + safeMessage(failure), evidence));
        } finally {
            // 查询和 Attention 使用实际观察到的当前子任务进度。
            // 诊断失败不能打断角色动作，也不能覆盖真正的任务结果。
            try { record.observeExecution(progress(), player.level().getGameTime()); }
            catch (RuntimeException ignoredDiagnosticFailure) { }
        }
    }

    private TaskState tickSemanticParent() {
        // 所有步骤都记为完成后，才报告整个目标成功。
        if (record.stepIndex() >= record.steps().size()) {
            terminalResult = completionResult();
            return TaskState.SUCCESS;
        }

        IntentTaskRecord.DecisionAnswer answer = record.takeAnswer();
        // 先处理调用者刚给的答复：取消、跳过、先补一个条件，或者修改目标后继续。
        if (answer != null) {
            if ("cancel".equals(answer.choice()) || "cancel_task".equals(answer.choice())) {
                mechanicalContinuations.clear();
                terminalResult = TaskResult.cancelled("cancelled at decision " + answer.decisionId());
                return TaskState.CANCELLED;
            }
            if ("respawn".equals(answer.choice()) || "spectate".equals(answer.choice())) {
                // 复活或旁观已由运行入口执行；恢复任务后重新检查当前语义步骤，
                // 不把这份身体生命周期答复再次交给业务适配器执行。
                return TaskState.RUNNING;
            }
            if ("skip".equals(answer.choice())) {
                // 用户明确跳过只推进清单，不把尚未确认的目标记为成功；已有尝试仍保留在历史中。
                discardContinuation(currentGoal());
                completeStep(TaskResult.fail("step skipped by explicit decision", Map.of("skipped", true)), true);
                return record.stepIndex() >= record.steps().size() ? TaskState.SUCCESS : TaskState.RUNNING;
            }
            if ("recover".equals(answer.choice()) || "replace_goal".equals(answer.choice())) {
                if ("replace_goal".equals(answer.choice())) discardContinuation(currentGoal());
                return applySemanticAnswer(answer);
            }
            var refused = persistAnswerParameters(answer);
            if (refused != null) return requestDecision(refused);
            // 参数已经成为当前持久步骤，消费屏障与恢复读取同一意图；地点只在创建动作时临时解析。
            return begin(AbilityAdapter.adapt(resolvedCurrentGoal(), player, runtime, continuationFor(currentGoal())));
        }

        // 正在走路就继续走这条路，不能每一刻都重新创建“去目的地”的任务。
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

    IntentTaskRecord.DecisionSnapshot persistAnswerParameters(IntentTaskRecord.DecisionAnswer answer) {
        Goal previous = currentGoal();
        JsonObject priorFailure = currentFailureResult();
        // 先看旧回执再接收新参数；不能先改Goal，使不确定消费的失败记录失去匹配后放开普通重试。
        if ("retry".equals(answer.choice()) && !RecoveryAdvisor.ordinaryRetryAllowed(priorFailure))
            return RecoveryAdvisor.retryRefused(previous, priorFailure);
        Goal updated = AbilityAdapter.goalFromAnswer(previous, answer);
        runtime.validateGoal(updated);
        if (record.updateCurrentParameters(updated.parameters())) {
            reconcileContinuation(previous, updated);
            runtime.semanticPlanChanged(record, currentGoal(), true);
            invalidateProtectionCache();
        }
        return null;
    }

    private void reconcileContinuation(Goal previous, Goal updated) {
        UUID continuation = mechanicalContinuations.remove(continuationKey(previous));
        if (continuation == null) return;
        JsonObject before = previous.parameters(), after = updated.parameters();
        before.remove("snapshot_id"); after.remove("snapshot_id");
        // 只刷新观察时继续原机械施工；材料策略、接口或其他真实参数改变后，旧路线与已确认前缀不能授权新方案。
        if (before.equals(after)) mechanicalContinuations.put(continuationKey(updated), continuation);
        else CreateMechanicalPower.discardContinuation(continuation);
    }

    private TaskState begin(IntentAction action) {
        // 目标转换后可能是“还在准备”“直接给出结果”“做一项动作”“等待条件”或“需要询问”。
        if (action == IntentAction.Pending.INSTANCE) return TaskState.RUNNING;
        if (action instanceof IntentAction.Report report) {
            if (!report.result().success()) return failStep(TaskState.FAILED, report.result());
            if (report.verifiedPosition() != null) {
                record.retainInternalStepPosition(record.stepIndex(), report.verifiedPosition());
            }
            completeStep(report.result());
            return afterImmediate();
        }
        if (action instanceof IntentAction.Native nativeAction) {
            // 某些动作只是为了看清情况，例如靠近电梯读楼层；做完还得回来重新判断原目标。
            reobserveAfterChild=nativeAction.reobserveAfterSuccess();
            return beginNative(nativeAction.record());
        }
        if (action instanceof IntentAction.Chain nextChain) {
            // 一组内部动作按顺序做，记住做到第几个，每次只启动当前那个。
            chain = nextChain.actions();
            chainIndex = 0;
            return beginTool(chain.getFirst());
        }
        if (action instanceof IntentAction.Decision decision) {
            return requestDecision(decision.snapshot());
        }
        if (action instanceof IntentAction.Remember remember) {
            runtime.remember(remember.label(), remember.position(), remember.areaRole());
            record.retainInternalStepPosition(record.stepIndex(), remember.position());
            completeStep(TaskResult.ok("remembered " + remember.label(),
                    Map.of("label", remember.label(),
                            "area_role", remember.areaRole().id())));
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
        // 根据内部工具名找到实现，例如 goto 对应移动；缺少实现时报告失败，不假装接单成功。
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
            withExplicitAreaProtection(() -> {
                // 工具原本会把任务交给总调度器；这里先接住，把它当作总任务里的小步骤执行。
                TaskDispatch.captureNext(captured::set, () ->
                        tool.onGameCall(childCallId, action.arguments(), player, immediate::set));
                return null;
            });
        } catch (RuntimeException exception) {
            return failStep(
                    TaskState.FAILED,
                    TaskResult.fail("ability " + currentGoal().ability()
                            + " could not start: " + safeMessage(exception)));
        }

        if (captured.get() != null) {
            // 工具交回一张任务单，说明还要在游戏里继续做；立即返回的文字不能当作已完成。
            return beginNative(captured.get());
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

    private TaskState beginNative(TaskRecord nextRecord) {
            // 原生加工消费前统一留下跨重启边界；附魔仍保留旧命名空间，水中转化与包装子任务也不能重复投料。
            if (nextRecord instanceof NativeSubmissionTaskRecord consumption)
                NativeSubmissionBinding.bind(consumption, record, runtime);
            retainBuildProject(nextRecord);
            // 记住这一步的任务单，创建对应执行代码，只做第一次准备；后续每刻继续同一个对象。
            childRecord = nextRecord;
            childRecord.setState(TaskState.RUNNING);
            childRecord.markStarted(player.level().getGameTime());
            child = withExplicitAreaProtection(() -> TaskFactory.create(player, childRecord));
            try {
                withExplicitAreaProtection(() -> {
                    child.start(player);
                    return null;
                });
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

    private TaskState tickChild() {
        // 先检查小任务自己的截止时间。总任务暂停时，这个时间目前不会一起往后推。
        if (player.level().getGameTime() >= childRecord.getDeadlineGameTime()) {
            childRecord.setState(TaskState.TIMEOUT);
        } else {
            try {
                childRecord.setState(withExplicitAreaProtection(() -> child.tick(player)));
            } catch (RuntimeException exception) {
                childRecord.setState(TaskState.FAILED);
                childRecord.setResult(TaskResult.fail(
                        "child tick failed: " + safeMessage(exception)));
            }
        }
        retainBuildProject(childRecord);
        return childRecord.getState().isTerminal() ? finishChild() : TaskState.RUNNING;
    }

    private void retainBuildProject(TaskRecord child) {
        // 施工与供料共用冻结蓝图；只从这两类任务保留工程编号，恢复时不会重新找地或生成建筑。
        var plan = child instanceof BuildTaskRecord build ? build
                : child instanceof SemanticBuildSupplyTaskRecord supply ? supply.plan
                : null;
        if (plan != null && record.retainBuildProject(plan.projectId(), plan.projectProtectionLabels())) {
            invalidateProtectionCache();
        }
    }

    private TaskState finishChild() {
        // 小任务做完后先收结果、松开按键，再决定做下一步还是询问；“材料取完了”还不等于“房子建好了”。
        Task finishingChild = child;
        TaskRecord finishingRecord = childRecord;
        boolean reobserve=reobserveAfterChild;
        TaskState state = finishingRecord.getState();
        TaskResult result;
        try {
            TaskState finalState = state;
            result = withExplicitAreaProtection(() -> finishingChild.result(finalState));
        } catch (RuntimeException exception) {
            result = TaskResult.fail("child result failed: " + safeMessage(exception));
            state = TaskState.FAILED;
        } finally {
            releaseBody();
            if (child == finishingChild) clearChild();
        }
        if (result == null) result = defaultResult(state);
        // 如果只是靠近电梯读到了楼层，就重新判断该去哪层，不能把“读到楼层”当成“已到目的地”。
        if(result.success() && reobserve) return TaskState.RUNNING;
        if (finishingRecord instanceof BuildTaskRecord
                && MachineAbilityAdapter.supports(currentGoal().ability())) {
            // 机器方块搭好了，只能证明外形完成；不能据此说机器已经通电、运转或产出了物品。
            Map<String, Object> machineData = new LinkedHashMap<>(result.data());
            machineData.put("machine_geometry_verified", result.success());
            machineData.put("machine_production_verified", false);
            machineData.put("verification_scope", "Mod-compiled physical targets and confirmed native effects; inspect menus, interfaces and actual production separately");
            result = new TaskResult(result.success(), result.message(), result.timedOut(), result.interrupted(), machineData);
        }
        InternalPositionReceipt.Position internalPosition = result.success()
                && finishingRecord instanceof InternalPositionReceipt receipt
                ? receipt.internalVerifiedPosition() : null;
        if (internalPosition != null) {
            record.retainInternalStepPosition(record.stepIndex(), new Goal.WorldPosition(
                    internalPosition.x(), internalPosition.y(), internalPosition.z(),
                    internalPosition.dimension()));
        }
        if (result.success()
                && finishingRecord instanceof InternalAreaProtectionReceipt receipt) {
            record.retainInternalAreaProtections(
                    record.stepIndex(), receipt.internalAreaProtections());
            invalidateProtectionCache();
        }
        if (!result.success()) {
            return failStep(state, result);
        }
        return finishToolSuccess(result);
    }

    private TaskState finishToolSuccess(TaskResult result) {
        // 内部动作串还没做完就继续下一个；全部成功后，才把这一个目标步骤记为完成。
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
        // recover 表示“先做这件事，再重试原任务”；replace_goal 表示“原来这一步改做别的”。
        JsonObject details = answer.details();
        if (!details.has("goal") || !details.get("goal").isJsonObject()) {
            return requestDecision(RecoveryAdvisor.invalidSemanticAnswer(
                    currentGoal(), answer.choice() + " requires details.goal",
                    currentFailureResult()));
        }
        Goal semanticGoal;
        try {
            semanticGoal = Goal.fromJson(details.getAsJsonObject("goal"));
            runtime.validateGoal(semanticGoal);
        } catch (RuntimeException exception) {
            return requestDecision(RecoveryAdvisor.invalidSemanticAnswer(
                    currentGoal(), safeMessage(exception), currentFailureResult()));
        }
        if ("recover".equals(answer.choice())) {
            record.discardInternalStepPosition(record.stepIndex());
            record.insertRecovery(semanticGoal);
            runtime.semanticPlanChanged(record, semanticGoal, false);
        } else {
            record.discardInternalStepPosition(record.stepIndex());
            record.replaceCurrent(semanticGoal);
            runtime.semanticPlanChanged(record, semanticGoal, true);
        }
        invalidateProtectionCache();
        return TaskState.RUNNING;
    }

    private TaskState failStep(TaskState state, TaskResult result) {
        // 记下这次为什么失败，再询问接下来怎么办；保留总目标，等待重试、补条件、换目标或取消。
        TaskState failureState = state == null ? TaskState.FAILED : state;
        // 当前步骤失败或被放弃后，其位置不能再成为后续 prior_result 的依据。
        record.discardInternalStepPosition(record.stepIndex());
        captureMechanicalContinuation(currentGoal(), result);
        TaskResult failure = withEffectLedger(SemanticResultView.result(
                result == null ? TaskResult.fail("internal action failed") : result));
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

    /** 失败离开 Mod 前，统一附上已经发生和仍未完成的语义效果。 */
    private TaskResult withEffectLedger(TaskResult failure) {
        // 失败时一起告诉调用者：前面哪些事已经做了，当前和后面还有哪些没做，避免重复开工。
        Map<String, Object> data = new LinkedHashMap<>(failure.data());
        data.put("completed_effects", completedEffects());
        data.put("remaining_effects", remainingEffects());
        data.put("skipped_steps", skippedSteps());
        return new TaskResult(
                failure.success(), failure.message(), failure.timedOut(), failure.interrupted(),
                Map.copyOf(data));
    }

    private List<Map<String, Object>> completedEffects() {
        List<Map<String, Object>> effects = new ArrayList<>();
        for (IntentTaskRecord.StepSnapshot step : record.stepResults()) {
            // 跳过不提供完整目标的成功证明；既有部分效果仍在尝试历史中，跳过索引单独列出。
            if (step.skipped()) continue;
            Map<String, Object> effect = new LinkedHashMap<>();
            effect.put("step_index", step.index());
            effect.put("ability", step.ability());
            if (step.index() >= 0 && step.index() < record.steps().size()) {
                effect.put("outcome", SemanticResultView.message(record.steps().get(step.index()).outcome()));
            }
            effect.put("success", step.success());
            effect.put("summary", SemanticResultView.message(step.message()));
            Object confirmed = SemanticResultView.jsonValue(step.result());
            if (confirmed != null) effect.put("confirmed_effect", confirmed);
            effects.add(Map.copyOf(effect));
        }
        return List.copyOf(effects);
    }

    private List<Map<String, Object>> remainingEffects() {
        List<Map<String, Object>> effects = new ArrayList<>();
        for (int index = record.stepIndex(); index < record.steps().size(); index++) {
            Goal pending = record.steps().get(index);
            Map<String, Object> effect = new LinkedHashMap<>();
            effect.put("step_index", index);
            effect.put("state", index == record.stepIndex() ? "failed_current" : "pending");
            effect.put("ability", pending.ability());
            effect.put("outcome", SemanticResultView.message(pending.outcome()));
            effects.add(Map.copyOf(effect));
        }
        return List.copyOf(effects);
    }

    /** 只取仍对应当前步骤和当前目标的最近失败，避免修改目标后误用旧错误。 */
    private JsonObject currentFailureResult() {
        // 只有“当前这一步、当前这个目标”的失败才能拿来决定重试；改过目标后不能误用旧原因。
        List<IntentTaskRecord.AttemptSnapshot> attempts = record.attempts();
        if (attempts.isEmpty() || record.stepIndex() >= record.steps().size()) return null;
        IntentTaskRecord.AttemptSnapshot latest = attempts.getLast();
        Goal current = record.steps().get(record.stepIndex());
        if (latest.stepIndex() != record.stepIndex()
                || !latest.goal().toJson().equals(current.toJson())) {
            return null;
        }
        return latest.result();
    }

    private void captureMechanicalContinuation(Goal goal, TaskResult raw) {
        // 机械连接可能已经装好一部分。记住内部给的恢复编号，下次重试才能核对并接着做，避免重复装配。
        if (goal == null || !("maicraft:connect_mechanical_power".equals(goal.ability())
                || (MachineAbilityAdapter.MODIFY.equals(goal.ability())
                    && goal.parameters().has("operation")
                    && "connect_mechanical_power".equals(goal.parameters().get("operation").getAsString())))) return;
        String key = continuationKey(goal);
        Object value = raw == null || raw.data() == null
                ? null : raw.data().get("continuation_token");
        if (value == null) {
            CreateMechanicalPower.discardContinuation(mechanicalContinuations.remove(key));
            return;
        }
        try {
            UUID next = UUID.fromString(String.valueOf(value));
            UUID prior = mechanicalContinuations.put(key, next);
            if (!next.equals(prior)) CreateMechanicalPower.discardContinuation(prior);
        } catch (IllegalArgumentException invalid) {
            CreateMechanicalPower.discardContinuation(mechanicalContinuations.remove(key));
        }
    }

    private UUID continuationFor(Goal goal) {
        return goal == null ? null : mechanicalContinuations.get(continuationKey(goal));
    }

    private void discardContinuation(Goal goal) {
        if (goal != null) CreateMechanicalPower.discardContinuation(
                mechanicalContinuations.remove(continuationKey(goal)));
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
        // 先等到允许检查的时间，再看天色、血量或饱食度；条件没满足就继续等，不主动做其他事。
        if (player.level().getGameTime() < wait.notBeforeGameTime()) return TaskState.RUNNING;
        if (!wait.condition().satisfiedBy(player)) return TaskState.RUNNING;
        String condition = wait.condition().id();
        wait = null;
        completeStep(TaskResult.ok("wait condition satisfied: " + condition));
        return afterImmediate();
    }

    private TaskState afterImmediate() {
        if (record.stepIndex() >= record.steps().size()) {
            terminalResult = completionResult();
            return TaskState.SUCCESS;
        }
        return TaskState.RUNNING;
    }

    private void completeStep(TaskResult result) {
        completeStep(result, false);
    }

    private void completeStep(TaskResult result, boolean skipped) {
        result = SemanticResultView.result(result);
        int index = record.stepIndex();
        if (skipped) record.discardInternalStepPosition(index);
        Goal goal = record.steps().get(index);
        record.addStepResult(new IntentTaskRecord.StepSnapshot(
                index, goal.ability(), result.success(), result.message(), result.toJson(), skipped));
        runtime.stepProcessed(record);
        if (!result.success() && !skipped) terminalResult = result;
    }

    private Goal currentGoal() {
        return record.steps().get(record.stepIndex());
    }

    /** 将“前一步找到的地方”解析为内部已确认位置，实际目标仍保留原来的语义关系。 */
    private Goal resolvedCurrentGoal() {
        // “去前面那一步找到的地方”要换成 Mod 自己确认过的位置，不能只凭文字猜坐标。
        Goal goal = currentGoal();
        Goal.SemanticTarget target = goal.target();
        if (target == null || !"prior_result".equals(target.kind())) return goal;
        Goal.WorldPosition position = PriorResultResolver.resolve(
                record, target, player.level().dimension().location().toString());
        return position == null ? goal : goal.withTarget(new Goal.SemanticTarget(
                "coordinates", target.label(), position, target.relation()));
    }

    @Override
    public void stop(LocalPlayer ignored, StopReason reason) {
        // 临时暂停只通知小任务停下，保留它做到哪；取消或离开旧玩家则还要收取结果、移走小任务。
        Task stoppingChild = child;
        TaskRecord stoppingRecord = childRecord;
        if (stoppingChild == null) {
            releaseBody();
            return;
        }
        if (reason == StopReason.PREEMPTED) {
            try {
                withExplicitAreaProtection(() -> { stoppingChild.stop(player, reason); return null; });
            } catch (RuntimeException ignoredFailure) {
                // 保留同一个子任务及其逻辑进度，等待恢复后继续。
            } finally {
                releaseBody();
            }
            return;
        }

        try {
            try {
                withExplicitAreaProtection(() -> { stoppingChild.stop(player, reason); return null; });
            } catch (RuntimeException ignoredFailure) {
                // 即使停止动作失败，下方仍尝试取得结果并清理逻辑资源。
            }
            if (stoppingRecord != null && !stoppingRecord.getState().isTerminal()) {
                stoppingRecord.setState(TaskState.CANCELLED);
            }
            try {
                TaskState state = stoppingRecord == null
                        ? TaskState.CANCELLED : stoppingRecord.getState();
                TaskResult cleanupResult = withExplicitAreaProtection(() -> stoppingChild.result(state));
                if (stoppingRecord != null) stoppingRecord.setResult(cleanupResult);
                retainInterruptedChild(state, cleanupResult);
            } catch (RuntimeException ignoredFailure) {
                retainInterruptedChild(TaskState.CANCELLED, TaskResult.fail(
                        "Child cleanup could not confirm its effects; inspect before another operation.",
                        Map.of("outcome_uncertain", true, "mechanical_retry_allowed", false)));
            }
        } finally {
            releaseBody();
            if (child == stoppingChild) clearChild();
        }
    }

    @Override
    public TaskResult result(TaskState terminal) {
        // 总任务结束前，如果还有小任务就先停止；把部分完成的情况保留下来，并且只发一次结束消息。
        if (child != null) {
            stop(player, StopReason.REPLACED);
        }
        TaskResult result = terminalResult;
        if (result == null) {
            result = switch (terminal) {
                case SUCCESS -> completionResult();
                case TIMEOUT -> TaskResult.timeout("semantic task timed out");
                case CANCELLED -> TaskResult.cancelled("semantic task cancelled");
                default -> TaskResult.fail("semantic task failed");
            };
        }
        if (interruptedChildResult != null && terminal != TaskState.SUCCESS) {
            result = withInterruptedEffects(result, interruptedChildResult);
        }
        Map<String, Object> projectData = new LinkedHashMap<>(result.data());
        addBuildProjects(projectData);
        result = new TaskResult(result.success(), result.message(), result.timedOut(), result.interrupted(), projectData);
        if (!terminalPublished) {
            terminalPublished = true;
            record.terminal(terminal, result, player.level().getGameTime());
            runtime.terminal(record, terminal, result);
        }
        return result;
    }

    private void retainInterruptedChild(TaskState state, TaskResult result) {
        interruptedChildResult = SemanticResultView.result(result == null ? TaskResult.fail(
                "Child returned no effect evidence during interruption.",
                Map.of("outcome_uncertain", true, "mechanical_retry_allowed", false)) : result);
        if (record.stepIndex() < record.steps().size()) {
            record.addAttempt(new IntentTaskRecord.AttemptSnapshot(record.stepIndex(), currentGoal(), state,
                    interruptedChildResult.message(), interruptedChildResult.toJson(), player.level().getGameTime()));
        }
    }

    /** 保留父任务的结束原因，同时带上子任务已经发生的部分效果。 */
    static TaskResult withInterruptedEffects(TaskResult parent, TaskResult child) {
        TaskResult clean = SemanticResultView.result(child);
        Map<String, Object> data = new LinkedHashMap<>(parent.data());
        data.put("interrupted_child", Map.of("message", clean.message(), "data", clean.data()));
        for (String key : List.of("outcome_uncertain", "effects_started", "mechanical_retry_allowed")) {
            if (clean.data().containsKey(key)) data.put(key, clean.data().get(key));
        }
        return new TaskResult(parent.success(), parent.message(), parent.timedOut(), parent.interrupted(), data);
    }

    private TaskResult completionResult() {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (IntentTaskRecord.StepSnapshot step : record.stepResults()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", step.index());
            item.put("ability", step.ability());
            item.put("success", step.success());
            item.put("skipped", step.skipped());
            item.put("message", step.message());
            steps.add(item);
        }
        int skipped = record.skippedStepCount();
        // 剩余清单执行结束不等于原始目标全部达成，不能把被略过的建造、采集或地点记忆写成成功。
        String message = skipped == 0 ? record.goal().outcome()
                : "Finished the remaining work; " + skipped + " step(s) were skipped by explicit decision.";
        return TaskResult.ok(message, Map.of("task_id", record.externalId().toString(), "steps", steps,
                "skipped_step_count", skipped, "skipped_steps", skippedSteps(),
                "all_steps_succeeded", record.allStepsSucceeded()));
    }

    private List<Integer> skippedSteps() {
        return record.stepResults().stream().filter(IntentTaskRecord.StepSnapshot::skipped)
                .map(IntentTaskRecord.StepSnapshot::index).toList();
    }

    private static TaskResult parseImmediate(String json) {
        // 把立即回复的 JSON 换成统一结果；不合法的回复按工具失败处理，附加 data 暂存为 tool_data。
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
        reobserveAfterChild=false;
    }

    /** 只应用当前目标在 protected_labels 中明确点名的保护范围，观察过区域本身不代表要求保护它。 */
    private <T> T withExplicitAreaProtection(Supplier<T> operation) {
        refreshProtectionCache();
        return NavigationSafetyContext.withProtectedArea(
                cachedProtectedMutationCells, cachedForbiddenBodyCells, operation);
    }

    private void refreshProtectionCache() {
        // 只有当前目标明确点名要保护的区域才生效；把那些区域里不能挖、不能放、不能走的格子交给导航。
        String dimension = player.level().dimension().location().toString();
        int step = record.stepIndex();
        if (step == cachedProtectionStep && dimension.equals(cachedProtectionDimension)) return;
        cachedProtectionDimension = dimension;
        cachedProtectionStep = step;
        LongOpenHashSet mutation = new LongOpenHashSet();
        LongOpenHashSet body = new LongOpenHashSet();
        LinkedHashSet<String> requested = new LinkedHashSet<>(
                explicitProtectedLabels(currentGoal()));
        if ("maicraft:sequence".equals(record.goal().ability())) {
            requested.addAll(explicitProtectedLabels(record.goal()));
        }
        if (!requested.isEmpty()) {
            for (Map.Entry<Integer, List<InternalAreaProtectionReceipt.Footprint>> entry
                    : record.internalAreaProtectionReceipts().entrySet()) {
                if (entry.getKey() >= step) continue;
                for (InternalAreaProtectionReceipt.Footprint footprint : entry.getValue()) {
                    if (!dimension.equals(footprint.dimension())
                            || footprint.semanticLabel() == null
                            || !requested.contains(normalizeProtectionLabel(
                                    footprint.semanticLabel()))) continue;
                    footprint.protectedMutationCells().forEach(mutation::add);
                    footprint.forbiddenBodyCells().forEach(body::add);
                }
            }
        }
        cachedProtectedMutationCells = mutation.isEmpty()
                ? LongSets.emptySet() : LongSets.unmodifiable(mutation);
        cachedForbiddenBodyCells = body.isEmpty()
                ? LongSets.emptySet() : LongSets.unmodifiable(body);
    }

    private static Set<String> explicitProtectedLabels(Goal goal) {
        if (goal == null) return Set.of();
        return goal.protectionLabels().stream().map(IntentTask::normalizeProtectionLabel)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalizeProtectionLabel(String label) {
        return label.strip().toLowerCase(Locale.ROOT);
    }

    private void invalidateProtectionCache() {
        cachedProtectionStep = -1;
        cachedProtectionDimension = null;
        cachedProtectedMutationCells = LongSets.emptySet();
        cachedForbiddenBodyCells = LongSets.emptySet();
    }

    private void abandonChildAfterUnexpectedFailure() {
        Task failed = child;
        TaskRecord failedRecord = childRecord;
        if (failed == null) {
            clearChild();
            releaseBody();
            return;
        }
        try {
            try {
                failed.stop(player, StopReason.REPLACED);
            } catch (RuntimeException ignoredFailure) {
                // 停止动作出错后，仍给结果收尾一次释放逻辑资源的机会。
            }
            TaskState cleanupState = TaskState.FAILED;
            if (failedRecord != null) {
                if (!failedRecord.getState().isTerminal()) failedRecord.setState(cleanupState);
                cleanupState = failedRecord.getState();
            }
            try {
                TaskResult cleanupResult = failed.result(cleanupState);
                if (failedRecord != null && failedRecord.getResult() == null) {
                    failedRecord.setResult(cleanupResult);
                }
            } catch (RuntimeException ignoredFailure) {
                // 子任务收尾本身失败，也不能让整个语义任务失去询问和恢复入口。
            }
        } finally {
            releaseBody();
            if (child == failed) clearChild();
        }
    }

    /** 结束时分别尝试停导航输入与身体按键，任一失败都不妨碍另一项收尾。 */
    private void releaseBody() {
        try {
            InputDriver.halt(player);
        } catch (RuntimeException ignoredFailure) {
        }
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

    @Override
    public String name() {
        return "intent";
    }

    @Override
    public Map<String, Object> progress() {
        Map<String, Object> progress = new LinkedHashMap<>(child != null ? SemanticResultView.data(child.progress())
                : Map.of("phase", record.decisionSnapshot() != null ? "waiting_for_decision"
                : wait != null ? "waiting_for_condition" : "preparing_step"));
        addBuildProjects(progress);
        return Map.copyOf(progress);
    }

    private void addBuildProjects(Map<String, Object> data) {
        List<String> ids = record.steps().stream().filter(step -> "maicraft:build".equals(step.ability()))
                .map(Goal::parameters).filter(parameters -> parameters.has("project_id"))
                .map(parameters -> parameters.get("project_id").getAsString()).distinct().toList();
        if (ids.size() == 1) data.put("project_id", ids.getFirst());
        if (!ids.isEmpty()) data.put("build_projects", ids);
    }
}
