// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.child.ChildTaskRunner;
import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeedsOff;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TaskInput;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 目标推进器：把 LLM 给的一个目标逐刻推进到有结果。
 *
 * <p>每刻对当前步骤问能力的决定（{@link AbilityModule#decide}），按决定行事：
 * 直接给结果就收尾；要动手就经子任务运行器把任务跑完，任务的结果按决定要么就是这一步的结果，
 * 要么回来再决定一次（能力看得到刚结束的结果，成功了看这一步满足没有，失败了换办法）；
 * 要问 LLM 就把目标挂起，回答回来接着推进；要记地点就交给记忆；要等条件就先等，条件成立再重新决定。
 *
 * <p>一个目标里每个任务已经发生的事都不丢：最后由谁给出结论，前面任务确认的变化、没能确认的交互、
 * 试过的办法都并进最终结果。目标被取消、被替换、程序出错时也一样。
 *
 * <p>时限不在这里给：任务多久算卡住、最多做多久由任务自己的进度跟踪按自己的节奏判断，
 * 挖 64 个铁、长途出行这类正常的长活不会被一个统一的时限截停。
 *
 * <p>它本身是一个任务：控制循环每刻像推进其他任务一样推进它。推进中抛出的异常在这里转成
 * 程序错误的问题收场，正在跑的任务先按取消收尾，不留按住的键和开着的界面。
 *
 * <p>推进的记录 {@link GoalRun} 每次处境变化都存进目标运行存储；重启后从存储读回还没结束的
 * 记录，恢复为暂停，明确恢复后从当前步骤重新决定（进行中的任务不能跨重启恢复，能力会重新看现场）。
 */
public final class GoalRunner implements Task, SurvivalNeedsOff {
    private static final Logger LOG = LoggerFactory.getLogger(GoalRunner.class);

    private final Goal goal;
    private final AbilityRegistry registry;
    private final GoalRunStore store;
    private final RemembersPlaces remembers;

    private final GoalRun run;
    /** 当前任务（sequence 时是当前步骤的子目标）；两件事之间为 null。 */
    private ChildTaskRunner child;
    /** 当前任务的输入；钩子要看它。 */
    private TaskInput currentInput;
    /** 当前任务结束后要不要回来重新决定这一步。 */
    private boolean decideAgainAfterCurrent;
    /** 按顺序运行几个任务时，还没轮到的输入生成器。 */
    private Deque<Supplier<TaskInput>> queuedInputs;
    /** 在等的条件；不在等时为 null。 */
    private WaitCondition waiting;
    /** 这一刻之前不检查等待的条件。 */
    private long waitNotBefore;
    /** sequence：正在跑的步骤的推进器；步骤之间为 null。 */
    private GoalRunner stepRunner;
    /** 已经结束的任务的结果，按先后；sequence 时是已经结束的各步骤的结果，下标就是第几步。 */
    private final List<TaskResult> finishedResults = new ArrayList<>();
    /** 结束后的结果；没结束时为 null。 */
    private TaskResult result;

    /**
     * 这件事的许可关掉了生存需求（survival_needs=off，寻死这类）：控制循环据此不插任何生存需求。
     * sequence 正在跑某一步时按那一步的许可。
     */
    @Override
    public boolean survivalNeedsOff() {
        if (stepRunner != null) return stepRunner.survivalNeedsOff();
        return goal.permissions().survivalNeeds() == Permissions.SurvivalNeeds.OFF;
    }

    private GoalRunner(GoalRun run, AbilityRegistry registry, GoalRunStore store, RemembersPlaces remembers) {
        this.run = run;
        this.goal = run.goal();
        this.registry = registry;
        this.store = store;
        this.remembers = remembers;
        // 重启后恢复的 sequence：前面已经结束的步骤的结论从记录里接上，收尾时照样算进成败、并进事实。
        finishedResults.addAll(run.stepResults());
    }

    /** 下达一个新目标：分配编号、记一条进行中的目标运行并立即存盘。 */
    public static GoalRunner launch(Goal goal, AbilityRegistry registry, GoalRunStore store, RemembersPlaces remembers) {
        GoalRunner runner = new GoalRunner(new GoalRun(store.nextId(), goal), registry, store, remembers);
        runner.save();
        return runner;
    }

    /**
     * 恢复一条重启前还没结束的目标运行：恢复为暂停，明确恢复后才继续推进。
     * 进行中的任务不恢复——任务没法跨重启活下来，恢复后能力会重新看现场做决定。
     * sequence 的步骤记录不单独恢复：它们随所属的 sequence 推进到那一步时自己接上。
     */
    public static GoalRunner restore(GoalRun restored, AbilityRegistry registry, GoalRunStore store,
                                     RemembersPlaces remembers) {
        if (!restored.unfinished()) {
            throw new IllegalStateException("目标运行 " + restored.id() + " 已经结束，不能恢复");
        }
        if (restored.parentRunId() != GoalRun.NO_PARENT) {
            throw new IllegalArgumentException("目标运行 " + restored.id() + " 是 sequence "
                    + restored.parentRunId() + " 的一步，随所属的 sequence 恢复");
        }
        if (restored.state() != GoalRunState.PAUSED) {
            restored.pause();
        }
        GoalRunner runner = new GoalRunner(restored, registry, store, remembers);
        runner.save();
        return runner;
    }

    /** 这条推进的记录；LLM 查询目标进展时看的就是它。 */
    public GoalRun run() {
        return run;
    }

    /** LLM 要求暂停：手上的任务先松开按键停手，目标停在原处，等恢复。 */
    public void pauseGoal() {
        if (result != null) {
            throw new IllegalStateException("目标运行 " + run.id() + " 已经结束，不能暂停");
        }
        if (run.state() == GoalRunState.PAUSED) {
            return;
        }
        pause();
        run.pause();
        save();
        LOG.info("目标 {} 暂停", run.id());
    }

    /** 恢复推进（LLM 要求恢复，或重启后明确恢复）：还挂着问题的接着等回答，其余从原处接着做。 */
    public void resumeGoal() {
        run.resume();
        save();
        LOG.info("目标 {} 恢复推进", run.id());
    }

    /**
     * 角色离开世界（退出到标题、断线、换世界）：手上的任务按"角色不在了"收尾、松开按键，目标本身不结束，
     * 存成暂停。下次进这个世界时它随存盘恢复为暂停，等 LLM 决定接不接着做——和重启游戏是同一回事。
     * sequence 正在跑的那一步同样停手存成暂停，恢复后从这一步接着做。
     */
    public void leaveWorld() {
        if (result != null) {
            return;
        }
        if (stepRunner != null) {
            // 步骤自己停手存盘；包着它的子任务运行器不收尾，收尾会把这一步当成结束。
            stepRunner.leaveWorld();
            forgetChild();
            stepRunner = null;
        } else {
            closeRunningChild(CloseReason.PLAYER_GONE);
        }
        queuedInputs = null;
        if (run.state() != GoalRunState.PAUSED) {
            run.pause();
        }
        save();
        LOG.info("目标 {} 随角色离开世界停在暂停，下次进这个世界时恢复", run.id());
    }

    /**
     * 现在挂着、等 LLM 回答的问题；没有时为 null。sequence 返回正在跑的那一步的问题，
     * 重启后那一步还没被重新拉起时，到存储里找它的记录。
     */
    public Question pendingQuestion() {
        if (run.question() != null) {
            return run.question();
        }
        if (goal.steps().isEmpty() || result != null) {
            return null;
        }
        if (stepRunner != null) {
            return stepRunner.pendingQuestion();
        }
        return storedStep(run.stepIndex()).map(GoalRun::question).orElse(null);
    }

    /** LLM 回答了挂着的问题：记下回答并存盘，之后接着推进（暂停中的目标先收下回答，恢复后不再等它）。 */
    public void answer(String text) {
        if (run.question() != null) {
            run.answer(text);
            save();
            LOG.info("目标 {} 的步骤 {} 得到回答：{}", run.id(), run.stepIndex(), text);
            return;
        }
        if (!goal.steps().isEmpty() && result == null) {
            // sequence 自己不提问，回答属于当前那一步：步骤正在跑就交给它，
            // 重启后还没被重新拉起就直接写进它在存储里的记录并存盘，拉起后照样接着走。
            if (stepRunner != null) {
                stepRunner.answer(text);
                return;
            }
            Optional<GoalRun> stored = storedStep(run.stepIndex()).filter(step -> step.question() != null);
            if (stored.isPresent()) {
                stored.get().answer(text);
                store.save(stored.get());
                return;
            }
        }
        throw new IllegalStateException("目标运行 " + run.id() + " 没有在等回答");
    }

    @Override public void start(TickContext context) {
        // 下达后还没推进就被取消的目标，控制循环下一刻照样会 start 它：已经结束就什么都不做。
        if (result != null) {
            return;
        }
        run.start(context.gameTick());
        save();
    }

    @Override public TickResult tick(TickContext context) {
        if (result != null) {
            return TickResult.finished(result);
        }
        // 暂停中的目标运行不推进：原样放着等恢复。
        if (run.state() == GoalRunState.PAUSED) {
            return TickResult.RUNNING;
        }
        try {
            return advance(context);
        } catch (RuntimeException exception) {
            // 决定、钩子或步骤之间的程序出错不连累控制循环：正在跑的任务先按取消收尾交代事实，再如实收场。
            LOG.warn("目标 {} 推进时出错", run.id(), exception);
            // 任务刚结束、钩子处理它时出的错：任务的结果已经在手上，照样并进来。
            TaskResult closed = child != null && child.finished()
                    ? child.result() : closeRunningChild(CloseReason.CANCELLED);
            TaskResult failure = TaskResult.failed("目标推进时程序出错", Problem.of(Problem.Kind.INTERNAL_ERROR,
                    exception.getClass().getSimpleName()
                            + (exception.getMessage() == null ? "" : "：" + exception.getMessage())));
            return settle(closed == null ? failure : failure.withFactsBefore(List.of(closed)), context);
        }
    }

    @Override public void pause() {
        // 被生存需求打断：正在跑的任务松开按键、保留进度；目标本身的处境不变。
        if (child != null && !child.finished()) {
            child.pause();
        }
    }

    @Override public TaskResult close(CloseReason reason) {
        if (result != null) {
            return result;
        }
        // 还在跑的任务按同样的原因收尾，交代已经发生的变化；目标的结论是"被取消 / 被替换 / 角色没了"。
        TaskResult closed = closeRunningChild(reason);
        TaskResult.Builder ending = TaskResult.builder(TaskResult.Status.CANCELLED, cancelledSummary(reason));
        if (closed != null) {
            ending.remaining(closed.remaining());
        }
        ending.remaining(stepsNotStarted(goal.steps().isEmpty() ? 0 : run.stepIndex() + 1));
        TaskResult cancelled = ending.build();
        settle(closed == null ? cancelled : cancelled.withFactsBefore(List.of(closed)), null);
        return result;
    }

    @Override public Interruptibility interruptibility(TickContext context) {
        if (result != null || run.state() == GoalRunState.PAUSED || child == null || child.finished()) {
            // 手上没有进行中的任务（在决定、等条件或暂停中），不急的事可以趁这个空当插进来。
            return Interruptibility.BETWEEN_ACTIONS;
        }
        Interruptibility childSays = child.interruptibility(context);
        if (run.state() == GoalRunState.AWAITING_ANSWER && childSays != Interruptibility.UNSAFE_TO_STOP) {
            // 任务已经停手等 LLM 回答，这段空当可能很长：吃口东西之类不急的事可以插进来。
            return Interruptibility.BETWEEN_ACTIONS;
        }
        return childSays;
    }

    @Override public String describe() {
        return switch (run.state()) {
            case AWAITING_ANSWER -> "目标 " + goal.ability() + " 在等回答：" + run.question().text();
            case PAUSED -> "目标 " + goal.ability() + " 已暂停（第 " + run.stepIndex() + " 步）";
            case FINISHED -> "目标 " + goal.ability() + " 已结束：" + result.summary();
            case RUNNING -> child != null && !child.finished()
                    ? "目标 " + goal.ability() + "：" + child.describe()
                    : waiting != null ? "目标 " + goal.ability() + " 在等：" + waiting.describe()
                    : "目标 " + goal.ability() + " 第 " + run.stepIndex() + " 步：看现场决定下一件做什么";
        };
    }

    /** 推进一刻：手上在跑任务就推进任务，否则重新看这一步该做什么。 */
    private TickResult advance(TickContext context) {
        if (run.state() == GoalRunState.AWAITING_ANSWER) {
            // 在等 LLM 回答：不决定、不推进任务，等 answer() 把它带回来。
            return TickResult.RUNNING;
        }
        if (!goal.steps().isEmpty()) {
            // sequence：目标里列了几步，逐步跑，不走能力的决定。
            return advanceSequence(context);
        }
        if (child != null && !child.finished()) {
            return tickChild(context);
        }
        return decideStep(context);
    }

    /** 推进当前任务一刻，结束了就按决定处理它的结果。 */
    private TickResult tickChild(TickContext context) {
        // 能力钩子说任务碰到了必须由 LLM 选择的情况：任务停手，停下来问。
        Question during = module().hooks().duringTask(stepContext(context), currentInput);
        if (during != null) {
            child.pause();
            run.ask(during);
            save();
            return TickResult.RUNNING;
        }
        TickResult tickResult = child.tick(context);
        if (tickResult instanceof TickResult.Running) {
            return TickResult.RUNNING;
        }
        return taskEnded(((TickResult.Finished) tickResult).result(), context);
    }

    /** 一个任务结束了（包括启动就失败）：先给能力钩子看一眼，再按决定走。 */
    private TickResult taskEnded(TaskResult taskResult, TickContext context) {
        AfterTask after = module().hooks().afterTask(stepContext(context), currentInput, taskResult);
        if (after instanceof AfterTask.Replace replace) {
            // 钩子换了一个决定：这个任务已经发生的事照样记下，再按新决定走。
            finishedResults.add(taskResult);
            forgetChild();
            queuedInputs = null;
            return applyDecision(replace.decision(), context);
        }
        return onTaskDone(((AfterTask.Accept) after).result(), context);
    }

    /** 按决定处理任务的结果：回来再决定、轮到下一个排好的任务，或者就此给出目标的结果。 */
    private TickResult onTaskDone(TaskResult taskResult, TickContext context) {
        // 先记下"要不要回来再决定"再清空当前任务，清空会把标记一起抹掉。
        boolean decideAgain = decideAgainAfterCurrent;
        forgetChild();
        if (decideAgain) {
            // 成败都回去问能力：它从 taskResults() 看到这个结果，满足了就结束，失败了换办法或结束。
            finishedResults.add(taskResult);
            return TickResult.RUNNING;
        }
        if (taskResult.status() != TaskResult.Status.DONE) {
            // 没做成又没要求回来再决定：带着完整事实结束目标，排在后面的任务不再做。
            queuedInputs = null;
            return settle(taskResult, context);
        }
        if (queuedInputs != null && !queuedInputs.isEmpty()) {
            finishedResults.add(taskResult);
            return startTask(queuedInputs.removeFirst().get(), false, context);
        }
        return settle(taskResult, context);
    }

    /** 重新问能力对当前这一步的决定，并按决定行事。 */
    private TickResult decideStep(TickContext context) {
        // 在等条件：还没到检查时间，或条件还没成立就继续等；成立后重新决定。
        if (waiting != null) {
            if (context.gameTick() < waitNotBefore || !waiting.satisfied(context)) {
                return TickResult.RUNNING;
            }
            LOG.info("目标 {} 等的条件成立了：{}", run.id(), waiting.describe());
            waiting = null;
        }
        StepDecision decision = module().decide(stepContext(context));
        return applyDecision(decision, context);
    }

    /** 执行能力的决定；除直接给结果和等待外，决定不在这份代码里展开成别的含义。 */
    private TickResult applyDecision(StepDecision decision, TickContext context) {
        if (decision instanceof StepDecision.NotReady) {
            // 信息还不全（例如分几刻做的扫描没扫完）：下一刻再决定，不把没扫完当成没有。
            return TickResult.RUNNING;
        }
        if (decision instanceof StepDecision.Finish finish) {
            return settle(finish.result(), context);
        }
        if (decision instanceof StepDecision.Run runTask) {
            queuedInputs = null;
            return startTask(runTask.input(), runTask.decideAgain(), context);
        }
        if (decision instanceof StepDecision.RunInOrder inOrder) {
            // 按顺序运行几个任务：剩下的到轮到时才生成输入，保证副作用按顺序发生；先排好队再开第一个，
            // 第一个启动就失败时也能按同一条路处理。
            Deque<Supplier<TaskInput>> remaining = new ArrayDeque<>(inOrder.inputs());
            Supplier<TaskInput> first = remaining.removeFirst();
            queuedInputs = remaining;
            return startTask(first.get(), false, context);
        }
        if (decision instanceof StepDecision.Ask ask) {
            run.ask(ask.question());
            save();
            return TickResult.RUNNING;
        }
        if (decision instanceof StepDecision.Remember remember) {
            remembers.remember(remember.name(), remember.position());
            LOG.info("目标 {} 记住了地点 {}（{}, {}, {}）", run.id(), remember.name(),
                    remember.position().x(), remember.position().y(), remember.position().z());
            return TickResult.RUNNING;
        }
        if (decision instanceof StepDecision.Wait wait) {
            waiting = wait.condition();
            waitNotBefore = wait.notBeforeTick();
            return TickResult.RUNNING;
        }
        throw new IllegalStateException("不认识的决定：" + decision.getClass().getName());
    }

    /**
     * 开始当前步骤的一个任务：每个任务开始前都先给能力钩子看一眼（按顺序排着的也一样），再创建并启动。
     * 启动就失败的任务当场按结束处理，失败不会被悄悄吞掉、下一刻再原样重来。
     */
    private TickResult startTask(TaskInput input, boolean decideAgain, TickContext context) {
        module().hooks().beforeTask(stepContext(context), input);
        currentInput = input;
        decideAgainAfterCurrent = decideAgain;
        child = new ChildTaskRunner(ChildTaskRunner.NO_LIMIT);
        child.begin(input, registry.taskFactories(), context);
        if (child.finished()) {
            return taskEnded(child.result(), context);
        }
        return TickResult.RUNNING;
    }

    /**
     * 逐步跑 sequence：每一步是一个完整的目标推进，经子任务运行器跑完再轮到下一步。
     * 某步没做成时按这一步自己的 onFailure 决定整件事停下还是继续。
     */
    private TickResult advanceSequence(TickContext context) {
        if (child == null) {
            return beginStep(context);
        }
        TickResult tickResult = child.tick(context);
        if (tickResult instanceof TickResult.Running) {
            return TickResult.RUNNING;
        }
        return stepEnded(((TickResult.Finished) tickResult).result(), context);
    }

    /** 轮到一步：这一步重启前有没结束的记录就接着用它，否则开一条新的；启动就失败也按这一步结束处理。 */
    private TickResult beginStep(TickContext context) {
        int index = run.stepIndex();
        if (index >= goal.steps().size()) {
            // 步骤已经轮完却还没收尾，是调用方写错了：如实报程序错误，不悄悄当成完成。
            throw new IllegalStateException("sequence 的步骤已经轮完，却没有收尾");
        }
        Goal step = goal.steps().get(index);
        GoalRun record = storedStep(index)
                .orElseGet(() -> new GoalRun(store.nextId(), step, run.id(), index));
        if (record.state() == GoalRunState.PAUSED) {
            // 所属的 sequence 已经在推进，这一步跟着恢复；还挂着的问题照样等回答。
            record.resume();
        }
        stepRunner = new GoalRunner(record, registry, store, remembers);
        child = new ChildTaskRunner(ChildTaskRunner.NO_LIMIT);
        child.begin(stepRunner, context);
        if (child.finished()) {
            return stepEnded(child.result(), context);
        }
        return TickResult.RUNNING;
    }

    /** 一步结束：结果记下，按这一步的 onFailure 决定停下还是轮到下一步；轮完就给出整件事的结果。 */
    private TickResult stepEnded(TaskResult stepResult, TickContext context) {
        child = null;
        stepRunner = null;
        int index = run.stepIndex();
        finishedResults.add(stepResult);
        run.recordStepResult(stepResult);
        Goal step = goal.steps().get(index);
        if (stepResult.status() != TaskResult.Status.DONE && step.onFailure() == Goal.OnFailure.STOP) {
            return settle(sequenceResult(index + 1), context);
        }
        if (index + 1 >= goal.steps().size()) {
            return settle(sequenceResult(goal.steps().size()), context);
        }
        run.advanceToStep(index + 1);
        save();
        return beginStep(context);
    }

    /** 找这条 sequence 第 index 步还没结束的记录（重启前中断的）。 */
    private Optional<GoalRun> storedStep(int index) {
        return store.unfinished().stream()
                .filter(candidate -> candidate.parentRunId() == run.id())
                .filter(candidate -> candidate.stepOfParent() == index)
                .findFirst();
    }

    /**
     * sequence 的结论：全部做成就是完成；一步都没做成就是失败；其余是部分完成。
     * 没做成的步骤和还没开始的步骤都写进剩下的部分，问题取第一个没做成的步骤的问题。
     * 各步骤已经发生的事实由收尾统一并进来。
     */
    private TaskResult sequenceResult(int nextStep) {
        int total = goal.steps().size();
        int done = 0;
        Problem problem = null;
        List<String> remaining = new ArrayList<>();
        for (int i = 0; i < finishedResults.size(); i++) {
            TaskResult stepResult = finishedResults.get(i);
            if (stepResult.status() == TaskResult.Status.DONE) {
                done++;
                continue;
            }
            if (problem == null) problem = stepResult.problem();
            remaining.add(stepLabel(i) + "：" + stepResult.summary());
            remaining.addAll(stepResult.remaining());
        }
        remaining.addAll(stepsNotStarted(nextStep));
        if (done == total) {
            return TaskResult.done("按顺序做完了全部 " + total + " 步");
        }
        TaskResult.Status status = done == 0 && problem != null ? TaskResult.Status.FAILED : TaskResult.Status.PARTIAL;
        return TaskResult.builder(status, done + " / " + total + " 步做成")
                .problem(problem)
                .remaining(remaining)
                .build();
    }

    /** sequence 里从 fromStep 起还没开始的步骤；普通目标没有。 */
    private List<String> stepsNotStarted(int fromStep) {
        List<String> notStarted = new ArrayList<>();
        for (int i = fromStep; i < goal.steps().size(); i++) {
            notStarted.add(stepLabel(i) + "：没有开始");
        }
        return notStarted;
    }

    private String stepLabel(int index) {
        Goal step = goal.steps().get(index);
        String purpose = step.purpose() == null ? "" : "，" + step.purpose();
        return "第 " + (index + 1) + " 步（" + step.ability() + purpose + "）";
    }

    /** 当前任务已经结算完，把两件事之间的空当清出来。 */
    private void forgetChild() {
        child = null;
        currentInput = null;
        decideAgainAfterCurrent = false;
    }

    /** 收尾还在跑的任务（sequence 是当前步骤）并拿它交代的结果；没有在跑的返回 null。 */
    private TaskResult closeRunningChild(CloseReason reason) {
        if (child == null || child.finished()) {
            return null;
        }
        TaskResult closed = child.close(reason);
        forgetChild();
        stepRunner = null;
        queuedInputs = null;
        return closed;
    }

    /** 结束目标：把前面已经结束的任务的事实并进结论，记录随之存盘，之后本推进封存。 */
    private TickResult settle(TaskResult ending, TickContext context) {
        result = ending.withFactsBefore(finishedResults);
        run.finish(result, context == null ? -1 : context.gameTick());
        save();
        LOG.info("目标 {} 结束：{}", run.id(), result.summary());
        return TickResult.finished(result);
    }

    private void save() {
        store.save(run);
    }

    private AbilityModule module() {
        return registry.find(goal.ability()).orElseThrow(() ->
                new IllegalStateException("能力 " + goal.ability() + " 没有登记，目标做不下去"));
    }

    private StepContext stepContext(TickContext context) {
        List<String> answers = run.answers();
        List<TaskResult> results = List.copyOf(finishedResults);
        return new StepContext() {
            @Override public Goal goal() {
                return goal;
            }

            @Override public int stepIndex() {
                return run.stepIndex();
            }

            @Override public TickContext tick() {
                return context;
            }

            @Override public List<String> answers() {
                return answers;
            }

            @Override public List<TaskResult> taskResults() {
                return results;
            }
        };
    }

    private static String cancelledSummary(CloseReason reason) {
        return switch (reason) {
            case FINISHED -> "目标没有交代结果就结束了";
            case REPLACED -> "目标被新任务替换";
            case CANCELLED -> "目标被取消";
            case PLAYER_GONE -> "角色不在了，目标中止";
        };
    }
}
