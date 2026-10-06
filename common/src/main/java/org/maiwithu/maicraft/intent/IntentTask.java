package org.maiwithu.maicraft.intent;

import org.maiwithu.maicraft.core.task.build.BuildPreviewGate;

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
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.data.WorldTimeSemantics;
import org.maiwithu.maicraft.core.integration.create.CreateMechanicalPower;
import org.maiwithu.maicraft.core.integration.machine.MachineControlTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.MachineMenuOpenTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.MachineSnapshotRejection;
import org.maiwithu.maicraft.core.integration.machine.MachineSnapshots;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.base.NativeSubmissionTaskRecord;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.container.ContainerSearchScope;
import org.maiwithu.maicraft.core.task.supply.SemanticBuildSupplyTaskRecord;
import org.maiwithu.maicraft.core.task.trade.SemanticTradeTaskRecord;
import org.maiwithu.maicraft.core.task.cook.SemanticCookTaskRecord;
import org.maiwithu.maicraft.core.task.explore.SemanticExploreTaskRecord;
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
    // 每个原生子步骤已分别收尾，显式开菜单交给下一请求；总任务不能再误关这份有效菜单。
    @Override public boolean keepsGuiOnCompletion() { return true; }

    private final LocalPlayer player;
    private final IntentTaskRecord record;
    private final IntentRuntime runtime;
    /** 随移动任务单交付的已知可站立位置上限：数量再多寻路也只消费临近的少数几格。 */
    private static final int KNOWN_CELL_HANDOFF_LIMIT = 24;

    private Task child;
    private TaskRecord childRecord;
    private boolean reobserveAfterChild;
    /** 操作者取消的仅清理缓期：子任务在回收自有现场期间语义计划整体冻结，清完按取消交回。 */
    private boolean cancellationCleanup;
    private IntentAction.Wait wait;
    private List<IntentAction.Tool> chain = List.of();
    private int chainIndex;
    private TaskResult terminalResult;
    private TaskResult interruptedChildResult;
    private boolean terminalPublished;
    private long childSerial;
    /** 自带床回退：现成床失败已触发过一次回退步骤，同一个步骤内不重复回退。 */
    private boolean bedFallbackEngaged;
    /** 触发回退的那次失败原文，回执里说明为什么改用了自带床。 */
    private String bedFallbackFailure;
    /** 回退链锚定的床头坐标：入睡遮挡的换位重试必须回到刚放下的这张床，不按类型重扫。 */
    private BlockPos bedFallbackBedHead;
    /** 换位重试只做一次：换位后仍判遮挡就让两段失败诚实终局。 */
    private boolean bedFallbackRepositioned;
    /** 最近一次入睡尝试锚定的床头：sleep 子任务启动时记下，现成床遮挡的换位重试回到同一张床。 */
    private BlockPos sleepAttemptBedHead;
    /** 现成床遮挡的换位重试只做一次；重试后仍遮挡就进入回退或诚实终局（166 批七）。 */
    private boolean sleepRepositioned;
    /** 触发现成床换位重试的那次失败原文：两段终局如实带上，调用方不必翻历史对账。 */
    private String sleepRepositionFailure;
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

    @Override public boolean suppressesSurvivalReflexes() {
        // 首刻也要让寻死适配器获得执行机会，否则饥饿反射可能永远挡住明确的寻死请求。
        // 暂停、等待决定和步骤结束均自动撤销豁免，不依赖一次必须执行成功的“重新开保护”。
        return !record.paused() && record.decisionSnapshot() == null && !record.getState().isTerminal()
                && record.stepIndex() < record.steps().size()
                && SuicideAbilityAdapter.ABILITY.equals(currentGoal().ability())
                && (child == null || child.suppressesSurvivalReflexes());
    }

    @Override public boolean observeDeath(LocalPlayer body) {
        // 死亡事件发生在普通 tick 之前；先把这个子步骤写成已完成，再保存检查点，重生后就不会再次寻死。
        if (body != player || child == null || !child.observeDeath(body)) return false;
        childRecord.setState(TaskState.SUCCESS);
        record.setState(finishChild());
        return true;
    }

    @Override
    public boolean requestCancellationCleanup() {
        // 操作者取消时把缓期请求转给当前步骤子任务：只有子任务确认有必须逐刻回收的
        // 自有现场（如施工脚手架）才缓期；没有子任务或子任务不需要回收就按原路立即取消。
        if (child == null || childRecord == null || childRecord.getState().isTerminal()) return false;
        if (!child.requestCancellationCleanup()) return false;
        cancellationCleanup = true;
        return true;
    }

    @Override
    public TaskState tick(LocalPlayer ignored) {
        try {
            return tickSemanticParent();
        } catch (RuntimeException failure) {
            // 适配器或内部能力异常时保留失败与现场；由 failStep 按本步继续策略结算，不自动改成待答问题。
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
            try {
                Map<String, Object> observation = progress();
                record.observeExecution(observation, player.level().getGameTime());
                runtime.publishProgress(record, observation, player.level().getGameTime());
            }
            catch (RuntimeException ignoredDiagnosticFailure) { }
        }
    }

    private TaskState tickSemanticParent() {
        // 取消缓期只回收现场：答复消费、重翻译与步骤推进全部冻结，收尾完成即按取消交回。
        if (cancellationCleanup) return tickCancellationCleanup();
        // 所有步骤都记为完成后，按统一结算报告：存在事实失败（含被容忍的）整体仍报 FAILED。
        if (record.stepIndex() >= record.steps().size()) {
            return finishCompletion();
        }

        IntentTaskRecord.DecisionAnswer answer = record.takeAnswer();
        // 先处理调用者刚给的答复：取消、跳过、先补一个条件，或者修改目标后继续。
        if (answer != null) {
            if (childRecord instanceof SemanticExploreTaskRecord explore
                    && explore.isInterestDecision(answer.decisionId())) {
                // 兴趣发现的答复只流回伴随任务单；不能落进语义改参数或换目标的通用答复分支。
                // 暂停期间伴随任务不被 tick、租期冻结在暂停时刻，答复时刻补期一次，
                // 恢复后第一刻的截止检查不得用模型思考时长否决继续选择。
                explore.renewAfterInterestAnswer(player.level().getGameTime());
                explore.applyInterestAnswer(answer.choice());
                return TaskState.RUNNING;
            }
            if (answer.decisionId().equals(sleepGateDecision)) {
                // 白天门答复只在本分支消费；编号随即作废，迟到的重复答复不再匹配同一扇门。
                sleepGateDecision = null;
                if ("recover".equals(answer.choice())) {
                    // recover 的语义是「等到可睡窗口再睡」：消费后转入等待阶段，绝不对同一扇
                    // 关着的门重问（实机三连 4123f34f→c885717c→d3697403：每个 recover 都
                    // 换来同门新决策，睡觉永远到不了点击那一步）。
                    record.armSleepGateWait();
                    Constants.LOG.info("[maicraft-sleep] 白天门 recover 已消费（decision {}），转入等待原版可睡窗口",
                            answer.decisionId());
                    return TaskState.RUNNING;
                }
                // skip 与 cancel 落进下面的通用分支：skip 消费当前步骤、cancel 终结任务，都没有重提窗口。
            }
            if ("cancel".equals(answer.choice()) || "cancel_task".equals(answer.choice())) {
                mechanicalContinuations.clear();
                record.setCancelSource("answer_cancel");
                terminalResult = TaskResult.cancelled("cancelled at decision " + answer.decisionId(), "answer_cancel");
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
                return record.stepIndex() >= record.steps().size() ? finishCompletion() : TaskState.RUNNING;
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

        // 白天门 recover 的等待阶段：窗口关着就原地等，不重新翻译当前目标——翻译会再次撞上
        // 同一扇关着的门（发射点 AbilityAdapter.waitForNightDecision），重提就是这么来的。
        if (record.sleepGateWaiting() && child == null && wait == null && chain.isEmpty()) {
            if (!WorldTimeSemantics.canAttemptSleep(player.level())) return TaskState.RUNNING;
            record.disarmSleepGateWait();
            Constants.LOG.info("[maicraft-sleep] 可睡窗口已打开，恢复睡觉步骤");
            // 落到下方重新翻译：此刻门已开，翻译给出 goto+sleep 链而不是决策。
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
        // IntentAction 是 sealed 接口，漏写任何一个变体编译器都会报错；null 分支沿用旧 if 链的防御行为。
        return switch (action) {
            case null -> failStep(
                    TaskState.FAILED,
                    TaskResult.fail("intent adapter produced no executable action"));
            case IntentAction.Pending pending -> TaskState.RUNNING;
            case IntentAction.Report report -> {
                if (!report.result().success()) yield failStep(TaskState.FAILED, report.result());
                if (report.verifiedPosition() != null) {
                    record.retainInternalStepPosition(record.stepIndex(), report.verifiedPosition());
                }
                completeStep(report.result());
                yield afterImmediate();
            }
            case IntentAction.Native nativeAction -> {
                // 某些动作只是为了看清情况，例如靠近电梯读楼层；做完还得回来重新判断原目标。
                reobserveAfterChild = nativeAction.reobserveAfterSuccess();
                yield beginNative(nativeAction.record());
            }
            case IntentAction.Chain nextChain -> {
                // 一组内部动作按顺序做，记住做到第几个，每次只启动当前那个。
                chain = nextChain.actions();
                chainIndex = 0;
                yield beginTool(chain.getFirst());
            }
            case IntentAction.Decision decision -> switch (sleepGateRelayAction(
                    record.decisionSnapshot() != null,
                    isSleepGateDecision(decision.snapshot()),
                    record.sleepGateWaiting())) {
                // 决策还开着（未答）：原地等答复；已答 recover 的白天门在等待窗口：
                // 同一扇关着的门绝不换发新决策（与 explore 兴趣中继同一三态语义）。
                case PARK, HOLD_REISSUE -> TaskState.RUNNING;
                case ISSUE -> requestDecision(decision.snapshot());
            };
            case IntentAction.Remember remember -> {
                // 先登记地点，再保留供后续步骤引用的内部位置，最后完成本步；本分支没有原生点击，也不验证目标处的地形。
                runtime.remember(remember.label(), remember.position(), remember.areaRole());
                record.retainInternalStepPosition(record.stepIndex(), remember.position());
                // 公开结果只确认运行时记忆已更新；坐标留在内部，后台 SQLite 保存完成不是此处等待的成功条件。
                completeStep(TaskResult.ok("remembered " + remember.label(),
                        Map.of("label", remember.label(),
                                "area_role", remember.areaRole().id())));
                yield afterImmediate();
            }
            case IntentAction.Wait nextWait -> {
                wait = nextWait;
                yield tickWait();
            }
            case IntentAction.Tool toolAction -> beginTool(toolAction);
        };
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
            if (captured.get() instanceof org.maiwithu.maicraft.core.task.move.MoveToTaskRecord moveRecord) {
                // 语义层只传递输入数据：本计划验证过与已登记地标中的可站立位置，供移动任务规划收敛失败时作分段中转；
                // 是否使用、何时使用由移动任务自己决定，这里不指定任何路线。
                moveRecord.withKnownStandableCells(knownStandableCells());
            }
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

    /** 交给移动任务单的已知可站立位置：本计划已验证的步骤回执加已登记地标，只留当前维度，超量截断。 */
    private List<BlockPos> knownStandableCells() {
        String dimension = player.level().dimension().location().toString();
        LinkedHashSet<Goal.WorldPosition> seen = new LinkedHashSet<>(record.internalKnownPositions());
        if (runtime != null) {
            for (IntentRuntime.Landmark landmark : runtime.landmarks()) {
                if (landmark.position() != null) seen.add(landmark.position());
            }
        }
        List<BlockPos> cells = new ArrayList<>();
        for (Goal.WorldPosition position : seen) {
            if (cells.size() >= KNOWN_CELL_HANDOFF_LIMIT) break;
            if (position == null || !dimension.equals(position.dimension())) continue;
            cells.add(new BlockPos(position.x(), position.y(), position.z()));
        }
        return cells;
    }

    private TaskState beginNative(TaskRecord nextRecord) {
            // 入睡尝试的床头锚点：遮挡失败的换位重试按它回到同一张床，不按类型重扫附近。
            if (nextRecord instanceof org.maiwithu.maicraft.core.task.sleep.SleepTaskRecord sleepRecord) {
                sleepAttemptBedHead = sleepRecord.bed;
            }
            if (nextRecord instanceof SemanticAcquireTaskRecord acquire && AcquireAbilityAdapter.ABILITY.equals(currentGoal().ability())) {
                // 恢复时覆盖新子任务临时捕获的位置；首次翻箱前复用现有检查点屏障，保证原范围已真正落盘。
                var scope = record.retainContainerSearchScope(acquire.storageScope == null
                        ? ContainerSearchScope.capture(player, acquire.storageSearchRadius) : acquire.storageScope);
                acquire.bindStorageScope(scope.orElse(null), NativeSubmissionBinding.barrier(record, runtime, "container_search_scope", () -> true));
            }
            // 公开取物、合成、烹饪、交易的 count 都是“再拿几件”：本步首次启动时记下背包已有数，目标 = 已有数 + count；
            // 之后重试、暂停和重启复用这份起始数，不会因重建子任务又多拿一轮。旧检查点留下的步骤仍按最终合计数执行。
            // 合成走内部取物任务、烹饪走烧炼任务、交易走交易任务；交互前取工具、施工补料等其他能力里的内部取物不进这条分支，仍给最终合计数。
            if (IntentTaskRecord.countsAdditionally(currentGoal().ability())) {
                if (nextRecord instanceof SemanticAcquireTaskRecord acquire)
                    record.retainAcquireBaseline(acquire.carriedByItem(player)).ifPresent(acquire::withAdditionalCount);
                else if (nextRecord instanceof SemanticTradeTaskRecord trade)
                    record.retainAcquireBaseline(trade.carriedByItem(player)).ifPresent(trade::withAdditionalCount);
                else if (nextRecord instanceof SemanticCookTaskRecord cook)
                    record.retainAcquireBaseline(cook.carriedByItem(player)).ifPresent(cook::withAdditionalCount);
            }
            // 单次提交先绑定父任务的持久身份，附魔、投料和聊天都不能因重启后新建子任务而重复执行。
            if (nextRecord instanceof NativeSubmissionTaskRecord submission)
                NativeSubmissionBinding.bind(submission, record, runtime);
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

    /**
     * 取消缓期分支：只驱动当前子任务回收自有现场，不再推进语义计划。子任务截止时间逐刻顺延，
     * 收尾本身有停滞放弃机制（外层槽位另有缓期硬上限），不会借缓期无限占用身体。
     */
    private TaskState tickCancellationCleanup() {
        if (child == null || childRecord == null) return TaskState.CANCELLED;
        childRecord.extendDeadlineTo(player.level().getGameTime() + 1);
        try {
            childRecord.setState(withExplicitAreaProtection(() -> child.tick(player)));
        } catch (RuntimeException exception) {
            childRecord.setState(TaskState.FAILED);
            childRecord.setResult(TaskResult.fail(
                    "cancellation cleanup tick failed: " + safeMessage(exception)));
        }
        retainBuildProject(childRecord);
        if (!childRecord.getState().isTerminal()) return TaskState.RUNNING;
        // 子任务收尾完成（或放弃）后只结算它的结果：不推进步骤、不发起询问，语义任务直接按取消交回。
        settleCancelledChild();
        return TaskState.CANCELLED;
    }

    /** 取消缓期后的子任务结算：取结果、松开身体、清掉子任务；不进入步骤推进或询问逻辑。 */
    private void settleCancelledChild() {
        Task finishingChild = child;
        TaskRecord finishingRecord = childRecord;
        try {
            TaskState state = finishingRecord.getState();
            TaskResult result = withExplicitAreaProtection(() -> finishingChild.result(state));
            if (finishingRecord.getResult() == null) finishingRecord.setResult(result);
            retainInterruptedChild(state, result);
        } catch (RuntimeException ignoredFailure) {
            retainInterruptedChild(TaskState.CANCELLED, TaskResult.fail(
                    "Child cleanup could not confirm its effects; inspect before another operation.",
                    Map.of("outcome_uncertain", true, "mechanical_retry_allowed", false)));
        } finally {
            releaseBody();
            if (child == finishingChild) clearChild();
        }
    }

    private TaskState tickChild() {
        // 语义层在这里观察跑图任务单上的待询问发现并转成正式决策；动作任务不得直接调 runtime.decision。
        if (childRecord instanceof SemanticExploreTaskRecord explore) {
            TaskState relayed = relayExploreInterestDecision(explore);
            if (relayed != null) return relayed;
        }
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
        // 接近设备期间场地也可能变化；原观察已消费时仍按任务冻结的范围返回新现场，不让模型另发勘测。
        if (!result.success()) result = withLatestMachineSnapshot(result, finishingRecord);
        // 如果只是靠近电梯读到了楼层，就重新判断该去哪层，不能把“读到楼层”当成“已到目的地”。
        if(result.success() && reobserve) return TaskState.RUNNING;
        if (finishingRecord instanceof BuildTaskRecord
                && MachineAbilityAdapter.supports(currentGoal().ability())) {
            // 机器方块搭好了，只能证明外形完成；不能据此说机器已经通电、运转或产出了物品。
            Map<String, Object> machineData = new LinkedHashMap<>(result.data());
            machineData.put("machine_geometry_verified", result.success());
            machineData.put("machine_production_verified", false);
            machineData.put("verification_scope", "Mod-compiled physical targets and confirmed native effects; inspect menus, interfaces and actual production separately");
            result = result.withData(machineData);
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
            result = withBedFallbackMarker(result);
        }
        discardContinuation(currentGoal());
        completeStep(result);
        return afterImmediate();
    }

    /** 回退链走完且睡成：步骤回执如实记录用了自带床，以及当初为什么放弃现成床。 */
    private TaskResult withBedFallbackMarker(TaskResult result) {
        if (!bedFallbackEngaged) return result;
        Map<String, Object> data = new LinkedHashMap<>(result.data());
        data.put("used_carried_bed_fallback", true);
        if (bedFallbackFailure != null) data.put("village_bed_fallback_reason", bedFallbackFailure);
        bedFallbackEngaged = false;
        bedFallbackFailure = null;
        bedFallbackBedHead = null;
        bedFallbackRepositioned = false;
        sleepAttemptBedHead = null;
        sleepRepositioned = false;
        sleepRepositionFailure = null;
        return result.withData(data);
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
            Goal inserted = shareTerrainAuthorization(semanticGoal);
            record.insertRecovery(inserted);
            runtime.semanticPlanChanged(record, inserted, false);
        } else {
            record.discardInternalStepPosition(record.stepIndex());
            record.replaceCurrent(semanticGoal);
            runtime.semanticPlanChanged(record, semanticGoal, true);
        }
        invalidateProtectionCache();
        return TaskState.RUNNING;
    }

    /**
     * 同一条任务链内已获回答的地形授权对整条链生效：决策答复或失败步骤任一侧带着 may_alter_terrain=true 时，
     * 另一侧只在完全缺省（参数与偏好都没有这个字段）时补上，自动插入的前置步骤与重试的原步骤不再对同一授权重问一轮。
     * 任一侧显式给出的值（含 false）保持原样——授权范围不同的步骤仍各自如实询问；授权也只在本任务链内传递，不跨任务。
     */
    private Goal shareTerrainAuthorization(Goal answeredGoal) {
        Goal failedStep = currentGoal();
        SharedAuthorization shared = shareTerrainAuthorization(failedStep, answeredGoal);
        if (!shared.failedStep().equals(failedStep)) {
            record.updateCurrentParameters(shared.failedStep().parameters());
        }
        return shared.answeredGoal();
    }

    /** 参数或偏好任一处写明了 may_alter_terrain 都算表过态，缺省（字段缺席）才允许继承同链授权。 */
    private static boolean statesTerrainAuthorization(Goal goal) {
        return goal.parameters().has("may_alter_terrain") || goal.preferences().has("may_alter_terrain");
    }

    private static Goal withTerrainAuthorization(Goal goal) {
        JsonObject parameters = goal.parameters();
        parameters.addProperty("may_alter_terrain", true);
        return goal.withParameters(parameters);
    }

    /** 授权共享后的两侧目标；任一侧保持原样时就是传入的同一实例。 */
    record SharedAuthorization(Goal answeredGoal, Goal failedStep) {}

    static SharedAuthorization shareTerrainAuthorization(Goal failedStep, Goal answeredGoal) {
        boolean stepAuthorized = statesTerrainAuthorization(failedStep);
        boolean answerAuthorized = statesTerrainAuthorization(answeredGoal);
        if (stepAuthorized == answerAuthorized) return new SharedAuthorization(answeredGoal, failedStep);
        if (stepAuthorized) return new SharedAuthorization(withTerrainAuthorization(answeredGoal), failedStep);
        return new SharedAuthorization(answeredGoal, withTerrainAuthorization(failedStep));
    }

    private TaskState failStep(TaskState state, TaskResult result) {
        // 原生动作失败就结束这次执行并保留现场结果，不把内部站位、支撑或菜单故障转成 LLM 的选择题。
        TaskState failureState = state == null ? TaskState.FAILED : state;
        // 当前步骤失败或被放弃后，其位置不能再成为后续 prior_result 的依据。
        record.discardInternalStepPosition(record.stepIndex());
        captureMechanicalContinuation(currentGoal(), result);
        TaskResult failure = withEffectLedger(SemanticResultView.result(FailureObservation.attach(
                result == null ? TaskResult.fail("internal action failed") : result, player)));
        Goal failedGoal = currentGoal();
        // 现场和效果账本已经结清，知识提示从同一份失败事实生成并一起落盘，不再要求模型先重复观察。
        failure = RecoveryKnowledge.attach(failedGoal, failure);
        // 回退步骤已经用过一次还再失败：终局话术把两段失败都带全，调用方不必翻历史就能对账。
        // 换位重试后的再次失败会第二次走到这里，前缀已带第一段就不再叠一层。
        if (bedFallbackEngaged && bedFallbackFailure != null
                && !failure.message().startsWith("the carried-bed fallback also failed")) {
            failure = new TaskResult(false, "the carried-bed fallback also failed; the earlier attempt failed with: "
                    + bedFallbackFailure + "; " + failure.message(),
                    failure.timedOut(), failure.interrupted(), failure.data());
        }
        // 现成床的换位重试也失败过：终局话术同样两段带全（166 批七：单句 occluded 收场，
        // 调用方无从得知换位已经试过）。回退链的前缀可能已经叠了第一段，同样防双包。
        if (sleepRepositionFailure != null
                && !failure.message().startsWith("the reposition retry also failed")) {
            failure = new TaskResult(false, "the reposition retry also failed; the earlier attempt failed with: "
                    + sleepRepositionFailure + "; " + failure.message(),
                    failure.timedOut(), failure.interrupted(), failure.data());
        }
        record.addAttempt(new IntentTaskRecord.AttemptSnapshot(
                record.stepIndex(),
                failedGoal,
                failureState,
                failure.message(),
                failure.toJson(),
                player.level().getGameTime()));
        if (toleratesStepFailure(failureState)) {
            // on_failure=continue 只对确认失败放行：失败记入步骤账本后接续兄弟步骤，
            // 整体终态由完成结算统一裁决（存在事实失败仍报 FAILED）。
            completeToleratedFailure(failure);
            return TaskState.RUNNING;
        }
        // 现成床被遮挡、够不着或失效，且没有明确指定床区时，改放自带床再睡一次，
        // 不让睡觉目标随一张被挡住的现成床一起落空（166）。失败已入账本，回执随后如实记录用了自带床。
        TaskState fallback = trySleepCarriedBedFallback(failedGoal, failure, failureState);
        if (fallback != null) return fallback;
        var data = new LinkedHashMap<String, Object>(failure.data());
        data.put("requires_decision", false);
        terminalResult = new TaskResult(false, failure.message(), failure.timedOut(), failure.interrupted(), data);
        chain = List.of();
        chainIndex = 0;
        return failureState == TaskState.TIMEOUT ? TaskState.TIMEOUT : TaskState.FAILED;
    }

    /**
     * sleep 步骤失败时的遮挡恢复阶梯。现成床（含点名床）入睡判遮挡时，先换到床边另一个
     * 可交互站位重试一次（锚定同一张床头）；换位后仍遮挡，再按原有规则考虑自带床回退——
     * 背包有床且未点名床区时放床再睡，否则两段失败诚实终局（166 批七：走到床边后单句
     * occluded 收场、无换位无回退，因为换位重试原先只在自带床回退链内生效）。
     */
    private TaskState trySleepCarriedBedFallback(Goal goal, TaskResult failure, TaskState failureState) {
        String failureType = String.valueOf(failure.data().get("failure_type"));
        if (bedFallbackEngaged) {
            // 放下的床从走到的那格看不见：先换位再睡同一张床头，而不是让整夜耗在一次坏站位上。
            if (!bedFallbackRepositioned && failureState == TaskState.FAILED
                    && "occluded".equals(failureType) && bedFallbackBedHead != null) {
                IntentAction.Chain retry = AbilityAdapter.sleepRepositionRetry(player, bedFallbackBedHead);
                if (retry != null) {
                    bedFallbackRepositioned = true;
                    chain = retry.actions();
                    chainIndex = 0;
                    Constants.LOG.info("[maicraft-sleep] 回退床 {} 入睡判遮挡，换到床边可交互站位重试一次",
                            bedFallbackBedHead);
                    return TaskState.RUNNING;
                }
                Constants.LOG.info("[maicraft-sleep] 回退床 {} 入睡判遮挡，床边没有可站立换位，维持诚实失败",
                        bedFallbackBedHead);
            }
            return null;
        }
        if (failureState != TaskState.FAILED
                || !"maicraft:sleep".equals(goal.ability())) return null;
        // 现成床入睡判遮挡：先换位重试一次（锚定同一张床头），再谈换床或终局。
        if ("occluded".equals(failureType) && !sleepRepositioned && sleepAttemptBedHead != null) {
            IntentAction.Chain retry = AbilityAdapter.sleepRepositionRetry(player, sleepAttemptBedHead);
            if (retry != null) {
                sleepRepositioned = true;
                sleepRepositionFailure = failure.message();
                chain = retry.actions();
                chainIndex = 0;
                Constants.LOG.info("[maicraft-sleep] 现成床 {} 入睡判遮挡，换到床边可交互站位重试一次",
                        sleepAttemptBedHead);
                return TaskState.RUNNING;
            }
            Constants.LOG.info("[maicraft-sleep] 现成床 {} 入睡判遮挡，床边没有可站立换位",
                    sleepAttemptBedHead);
        }
        AbilityAdapter.CarriedBedPlan fallback = AbilityAdapter.carriedBedFallback(goal, player, failureType);
        if (fallback == null) return null;
        bedFallbackEngaged = true;
        bedFallbackFailure = failure.message();
        bedFallbackBedHead = fallback.bedHead();
        bedFallbackRepositioned = false;
        if (fallback.action() instanceof IntentAction.Chain fallbackChain) {
            chain = fallbackChain.actions();
            chainIndex = 0;
            Constants.LOG.info("[maicraft-sleep] 现成床不可用（failure_type={}），回退放置自带床: {}",
                    failureType, bedFallbackFailure);
            return TaskState.RUNNING;
        }
        // 没有安全落位或可睡窗口已关：这个决定本来就要调用方拍板，转成待答问题而不是吞掉。
        if (fallback.action() instanceof IntentAction.Decision decision) {
            Constants.LOG.info("[maicraft-sleep] 现成床不可用（failure_type={}），自带床缺少回退条件，转为决定",
                    failureType);
            return requestDecision(decision.snapshot());
        }
        return null;
    }

    /** 容忍边界：仅确认失败且还有兄弟步骤；超时与取消效果不确定或调用方主动停，一律全停。 */
    private boolean toleratesStepFailure(TaskState failureState) {
        return failureState == TaskState.FAILED && currentGoal().toleratesFailure()
                && record.stepIndex() + 1 < record.steps().size();
    }

    private void completeToleratedFailure(TaskResult failure) {
        Goal goal = record.steps().get(record.stepIndex());
        bedFallbackEngaged = false;
        bedFallbackFailure = null;
        bedFallbackBedHead = null;
        bedFallbackRepositioned = false;
        sleepAttemptBedHead = null;
        sleepRepositioned = false;
        sleepRepositionFailure = null;
        record.addStepResult(new IntentTaskRecord.StepSnapshot(
                record.stepIndex(), goal.ability(), false, failure.message(), failure.toJson(), false));
        runtime.stepProcessed(record);
        chain = List.of();
        chainIndex = 0;
    }

    /** 只为明确的机器结构变化补回最新观察，保留原生动作的已发生效果和重试限制。 */
    private TaskResult withLatestMachineSnapshot(TaskResult failure, TaskRecord failedRecord) {
        Object code = failure.data().get("failure_code");
        BlockPos center;
        int radius;
        if (failedRecord instanceof MachineMenuOpenTaskRecord menu && "machine_menu_structure_changed".equals(code)) {
            center = menu.request.center(); radius = menu.request.radius();
        } else if (failedRecord instanceof MachineControlTaskRecord control && "machine_structure_changed".equals(code)) {
            center = control.request.center(); radius = control.request.radius();
        } else return failure;
        Goal goal = currentGoal();
        if (goal.target() == null || goal.target().label() == null) return failure;
        Map<String, Object> data = new LinkedHashMap<>(failure.data());
        try {
            // 观察冻结的原设备位置，玩家取料或绕路后的脚位不能平移失败结果中的机器。
            var latest = MachineSnapshots.inspect(player, goal.target().label(), center, radius);
            String previousId = goal.parameters().has("snapshot_id") ? goal.parameters().get("snapshot_id").getAsString() : "";
            data.putAll(new MachineSnapshotRejection("machine_snapshot_changed", previousId, latest).details());
            data.put("failure_code", code);
        } catch (RuntimeException unavailable) {
            // 补充现场失败不能覆盖原生失败和消费不确定性；留下明确原因，避免模型把缺失观察当作空场地。
            Constants.LOG.warn("[maicraft] 无法补读结构变化后的机器现场: target={}, center={}, radius={}",
                    goal.target().label(), center, radius, unavailable);
            data.put("snapshot_refresh_failure", safeMessage(unavailable));
        }
        return new TaskResult(false, failure.message(), failure.timedOut(), failure.interrupted(), data);
    }

    /** 失败离开 Mod 前，统一附上已经发生和仍未完成的语义效果。 */
    private TaskResult withEffectLedger(TaskResult failure) {
        return withEffectLedger(failure, completedEffects(), remainingEffects(), skippedSteps());
    }

    static TaskResult withEffectLedger(TaskResult failure, List<Map<String, Object>> completed,
                                      List<Map<String, Object>> remaining, List<Integer> skipped) {
        // 结构改造可能已经放好并转正一个齿轮箱，随后才因下一格遮挡失败；不能用“尚无完整步骤”覆盖这些原生效果。
        Map<String, Object> data = new LinkedHashMap<>(failure.data());
        if (!data.containsKey("completed_effects")) data.put("completed_effects", completed);
        else if (!completed.isEmpty()) data.put("completed_step_effects", completed);
        // 子任务的未完成方块与编排中待执行的步骤分别保留，恢复时仍能使用整机差异决定下一次补丁。
        if (!data.containsKey("remaining_effects")) data.put("remaining_effects", remaining);
        else if (!remaining.isEmpty()) data.put("remaining_step_effects", remaining);
        data.put("skipped_steps", skipped);
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
        // 白天门决策记住编号：答复到达时按编号识别这扇门，走专属消费分支。
        sleepGateDecision = isSleepGateDecision(decision) ? decision.id() : null;
        runtime.decision(record, decision);
        return TaskState.RUNNING;
    }

    /** 白天门决策的 context 标记，与发射侧 AbilityAdapter.waitForNightDecision 保持同一字面量。 */
    static final String SLEEP_GATE_DECISION_KIND = "sleep_day_gate";

    /** 本次会话挂着的白天门决策编号；仅用于答复路由，检查点恢复后待答问题整体原样恢复。 */
    private UUID sleepGateDecision;

    static boolean isSleepGateDecision(IntentTaskRecord.DecisionSnapshot decision) {
        try {
            var kind = decision.context().get("decision_kind");
            return kind != null && kind.isJsonPrimitive()
                    && SLEEP_GATE_DECISION_KIND.equals(kind.getAsString());
        } catch (RuntimeException brokenContext) {
            // 上下文损坏时按普通决策走通用答复分支；异常路径带原因落日志，不静默吞掉。
            Constants.LOG.warn("[maicraft-sleep] 决策 context 解析失败，按普通决策处理", brokenContext);
            return false;
        }
    }

    /** 白天门中继处置：等答复、按住已答复门的重发、放行新决策——与 explore 兴趣中继同一三态。 */
    enum SleepGateRelayAction { PARK, HOLD_REISSUE, ISSUE }

    /**
     * 中继判定纯函数：
     * - 语义决策快照还开着（未被回答）：PARK，等 task action=answer；
     * - 白天门且 recover 已消费（等待阶段接管，窗口未开）：HOLD_REISSUE——此刻若放行发射点，
     *   翻译会对同一扇关着的门换发新决策编号，每个 recover 都换来一次重问（实机三连形态）；
     * - 其余（全新决策，或非白天门的普通决策）：ISSUE 照常发出。
     */
    static SleepGateRelayAction sleepGateRelayAction(
            boolean decisionSnapshotOpen, boolean gateDecision, boolean gateWaiting) {
        if (decisionSnapshotOpen) return SleepGateRelayAction.PARK;
        return gateDecision && gateWaiting
                ? SleepGateRelayAction.HOLD_REISSUE : SleepGateRelayAction.ISSUE;
    }

    /** 语义层观察动作任务单上的待询问发现并拉起决策；决策期间任务记录会暂停，等待 task action=answer。 */
    private TaskState relayExploreInterestDecision(SemanticExploreTaskRecord explore) {
        var finding = explore.pendingInterestFinding();
        if (finding == null) return null;
        InterestRelayAction action = exploreInterestRelayAction(
                explore, record.decisionSnapshot() != null);
        return switch (action) {
            case PASS_THROUGH -> null;
            case PARK -> TaskState.RUNNING;
            case ISSUE -> {
                UUID decisionId = UUID.randomUUID();
                explore.beginInterestDecision(decisionId);
                yield requestDecision(exploreInterestDecision(currentGoal(), decisionId, finding));
            }
        };
    }

    /** 中继对待询问发现的三种处置：放行伴随任务 tick、原地等答复、发起新决策。 */
    enum InterestRelayAction { PASS_THROUGH, PARK, ISSUE }

    /**
     * 中继判定纯函数：
     * - 语义决策快照还开着（问题未被回答）：原地 PARK，等 task action=answer；
     * - 决策已发出、答复已写回但伴随任务尚未消费（消费点在伴随任务 onTick，晚于本中继执行）：
     *   PASS_THROUGH 放行 tick——此刻绝不能对同一待询问发现再发新决策，否则每个答复都在
     *   消费前换来一次重提，continue 与 stop 双双失效，任务永远回不到推进态；
     * - 全新待询问发现：ISSUE 发起决策。
     */
    static InterestRelayAction exploreInterestRelayAction(
            SemanticExploreTaskRecord explore, boolean decisionSnapshotOpen) {
        if (decisionSnapshotOpen) return InterestRelayAction.PARK;
        if (explore.interestDecisionId() != null) {
            return explore.hasInterestAnswer()
                    ? InterestRelayAction.PASS_THROUGH : InterestRelayAction.PARK;
        }
        return InterestRelayAction.ISSUE;
    }

    /** 兴趣决策快照：finding 细节进 context 供回执核对，question 与选项面向模型陈述两种走向。 */
    static IntentTaskRecord.DecisionSnapshot exploreInterestDecision(
            Goal goal, UUID decisionId, SemanticExploreTaskRecord.InterestFinding finding) {
        JsonObject context = new JsonObject();
        context.addProperty("decision_kind", "explore_interest");
        context.addProperty("ability", goal.ability());
        context.add("goal", goal.toJson());
        JsonObject detail = new JsonObject();
        detail.addProperty("finding_id", finding.findingId());
        detail.addProperty("target_id", finding.targetId());
        detail.addProperty("x", finding.x());
        detail.addProperty("y", finding.y());
        detail.addProperty("z", finding.z());
        detail.addProperty("direction", finding.direction());
        detail.addProperty("distance_blocks", finding.distanceBlocks());
        context.add("finding", detail);
        String question = "A newly recorded terrain feature matches the declared exploration interests: "
                + finding.targetId() + " about " + finding.distanceBlocks() + " blocks to the "
                + finding.direction() + " at " + finding.x() + "," + finding.y() + "," + finding.z()
                + ". Its observation is already saved in exploration memory with an exploration: label. "
                + "continue keeps exploring and will not ask about this finding again; stop finishes the task "
                + "now, keeping every discovery in exploration memory without claiming the main target was verified.";
        return new IntentTaskRecord.DecisionSnapshot(decisionId, question, List.of(
                new IntentTaskRecord.DecisionOption("continue",
                        "Keep exploring; this finding will not trigger another question."),
                new IntentTaskRecord.DecisionOption("stop",
                        "Finish the task now; discoveries stay in exploration memory and the main target remains unverified.")),
                context.toString());
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
            return finishCompletion();
        }
        return TaskState.RUNNING;
    }

    /** 清单全部处理后的统一结算：部分失败也算完全失败，账本承担诚实披露。 */
    private TaskState finishCompletion() {
        TaskResult completion = completionResult();
        terminalResult = completion;
        return completion.success() ? TaskState.SUCCESS : TaskState.FAILED;
    }

    private void completeStep(TaskResult result) {
        completeStep(result, false);
    }

    private void completeStep(TaskResult result, boolean skipped) {
        result = SemanticResultView.result(result);
        bedFallbackEngaged = false;
        bedFallbackFailure = null;
        sleepAttemptBedHead = null;
        sleepRepositioned = false;
        sleepRepositionFailure = null;
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
                case CANCELLED -> TaskResult.cancelled("semantic task cancelled", record.getCancelSource());
                default -> TaskResult.fail("semantic task failed");
            };
        }
        if (interruptedChildResult != null && terminal != TaskState.SUCCESS) {
            result = withInterruptedEffects(result, interruptedChildResult);
        }
        Map<String, Object> projectData = new LinkedHashMap<>(result.data());
        addBuildProjects(projectData);
        // 执行期间被自卫带离工位的每一段经历都并入最终回执，避免模型把换了地点的产出误当成原地完成。
        JsonArray excursions = record.selfDefenseExcursions();
        if (!excursions.isEmpty()) projectData.put("self_defense_excursions", excursions);
        result = result.withData(projectData);
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
        TaskResult merged = parent.withData(data);
        // 子任务带着更具体的取消来源（如聊天命令尚未发出就被取消）时交给顶层回执，
        // 调用方才能区分“没发出去”与“发完才被叫停”，而不是只看到通用的接管取消。
        if (clean.cancelSource() != null && !clean.cancelSource().isBlank()) {
            merged = merged.withCancelSource(clean.cancelSource());
        }
        return merged;
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
            // 寻死的动作完成、保护恢复与尚未观察到的重生必须随默认完成回执交付，不能只留下“完成”摘要。
            if (SuicideAbilityAdapter.ABILITY.equals(step.ability()))
                item.put("confirmed_effect", SemanticResultView.jsonValue(step.result()));
            steps.add(item);
        }
        int skipped = record.skippedStepCount();
        // 改成检查机器后只能报告观察结果；请求中的期望产物、被替换的施工和被略过的目标都不是完成证据。
        int tolerated = toleratedFailureCount();
        if (tolerated > 0) {
            // 存在事实失败（含 on_failure=continue 容忍的失败）：整体终态 FAILED，
            // 全部已处理步骤改走失败账本披露，不在成功回执里夹带失败事实。
            String message = "Finished the remaining work, but " + tolerated + " step(s) failed and their "
                    + "on_failure=continue let later siblings run; a tolerated failure still ends the task "
                    + "as failed. Inspect completed_effects for what actually happened.";
            var data = new LinkedHashMap<String, Object>();
            data.put("task_id", record.externalId().toString());
            data.put("steps", steps);
            data.put("tolerated_failure_count", tolerated);
            data.put("skipped_step_count", skipped);
            data.put("completion_scope", "executed_steps");
            data.put("all_steps_succeeded", false);
            data.put("requires_decision", false);
            return withEffectLedger(new TaskResult(false, message, false, false, Map.copyOf(data)));
        }
        String message = skipped > 0
                ? "Finished the remaining work; " + skipped + " step(s) were skipped by explicit decision."
                : record.stepResults().size() == 1 ? record.stepResults().getFirst().message()
                : "Completed " + steps.size() + " current step(s); inspect their individual effect receipts.";
        return TaskResult.ok(message, Map.of("task_id", record.externalId().toString(), "steps", steps,
                "skipped_step_count", skipped, "skipped_steps", skippedSteps(),
                "completion_scope", "executed_steps",
                "all_steps_succeeded", record.allStepsSucceeded()));
    }

    /** 被 on_failure=continue 容忍的失败步骤：记过结果但没成功，也没有被显式跳过。 */
    private int toleratedFailureCount() {
        return (int) record.stepResults().stream()
                .filter(step -> !step.success() && !step.skipped()).count();
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
    public String describeCurrentAction() {
        // 语义任务不自己说话：此刻在干什么由一线子任务汇报。
        return child == null ? null : child.describeCurrentAction();
    }

    @Override
    public Map<String, Object> progress() {
        Map<String, Object> progress = new LinkedHashMap<>(child != null ? SemanticResultView.data(child.progress())
                : Map.of("phase", record.decisionSnapshot() != null ? "waiting_for_decision"
                : record.sleepGateWaiting() ? "waiting_for_sleep_window"
                : wait != null ? "waiting_for_condition" : "preparing_step"));
        addBuildProjects(progress);
        // 子任务表达的是执行意图，父任务暂停时实际已允许自救接手；公开状态按当前调度条件纠正。
        if (progress.containsKey("survival_reflexes_suppressed"))
            progress.put("survival_reflexes_suppressed", suppressesSurvivalReflexes());
        // 内部机器任务等待人工蓝图确认时，总任务同步展示真实等待原因，供调用者结束无效轮询。
        progress.putAll(BuildPreviewGate.waitingProgress(record));
        return Map.copyOf(hoistChildPlanning(progress));
    }

    /**
     * 包装类任务（施工、传送门准备、采集编排等）把一线子任务嵌在 {@code child} 键下，而进度
     * 门卫只读顶层标准键——子任务在规划期变化时包装任务顶层反而无话可说，规划心跳被整层埋没。
     * 子任务报出 {@code phase}/{@code calc}/{@code planning_seconds} 时把它们提到顶层（不覆盖
     * 顶层已有值），包装任务的静默窗与一线任务遵守同一条心跳纪律；phase 只在顶层没有时上提，
     * 心跳键即使顶层已有 phase 也照常上提——采集这类包装任务顶层 phase 恒定，卡住的一线子任务
     * 的单调心跳是门卫唯一能看到的变化。
     */
    static Map<String, Object> hoistChildPlanning(Map<String, Object> progress) {
        if (!(progress.get("child") instanceof Map<?, ?> childProgress)) {
            return progress;
        }
        Object phase = childProgress.get("phase");
        Object calc = childProgress.get("calc");
        Object planningSeconds = childProgress.get("planning_seconds");
        if (phase == null && calc == null && planningSeconds == null) return progress;
        Map<String, Object> hoisted = new LinkedHashMap<>(progress);
        if (phase != null) hoisted.putIfAbsent("phase", phase);
        if (calc != null) hoisted.putIfAbsent("calc", calc);
        if (planningSeconds != null) hoisted.putIfAbsent("planning_seconds", planningSeconds);
        return hoisted;
    }

    private void addBuildProjects(Map<String, Object> data) {
        List<String> ids = record.steps().stream().filter(step -> "maicraft:build".equals(step.ability()))
                .map(Goal::parameters).filter(parameters -> parameters.has("project_id"))
                .map(parameters -> parameters.get("project_id").getAsString()).distinct().toList();
        if (ids.size() == 1) data.put("project_id", ids.getFirst());
        if (!ids.isEmpty()) data.put("build_projects", ids);
    }
}
