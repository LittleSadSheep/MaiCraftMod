// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.child.ChildTaskRunner;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
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
 * 直接给结果就收尾；要动手就经子任务运行器把任务跑完，任务成功后按决定决定是"重新看这一步
 * 是否满足"还是"直接算完成"；要问 LLM 就把目标挂起，回答回来接着推进；要记地点就交给记忆；
 * 要等条件就先等，条件成立再重新决定。步骤的任务失败时，失败原样传播成目标的失败。
 *
 * <p>它本身是一个任务：控制循环每刻像推进其他任务一样推进它，不用为它单开一条路。
 * 推进中抛出的异常在这里转成程序错误的问题收场，不连累调用方一起崩。
 *
 * <p>推进的记录 {@link GoalRun} 每次处境变化都存进目标运行存储；重启后从存储读回还没结束的
 * 记录，恢复为暂停，明确解除暂停后从当前步骤重新决定（进行中的任务不能跨重启恢复，能力会重新看现场）。
 */
public final class GoalRunner implements Task {
    private static final Logger LOG = LoggerFactory.getLogger(GoalRunner.class);

    /**
     * 一步的子任务允许推进多久（默认 10 分钟）。卡住判定本来由各任务自己的进度跟踪负责，
     * 这里只是内核给的兜底时限，防"某个任务自己永远不结束"；离线测试用 {@link #launchWithBudget} 指定更短的时限。
     */
    static final long DEFAULT_STEP_BUDGET_TICKS = 20L * 60 * 10;

    private final Goal goal;
    private final AbilityRegistry registry;
    private final GoalRunStore store;
    private final RemembersPlaces remembers;
    private final long stepBudgetTicks;

    private final GoalRun run;
    /** 当前步骤的任务；步骤之间为 null。 */
    private ChildTaskRunner child;
    /** 当前步骤的任务输入；钩子要看它。 */
    private TaskInput currentInput;
    /** 当前任务成功后要不要重新看这一步是否满足。 */
    private boolean recheckAfterCurrent;
    /** 按顺序运行几个任务时，还没轮到的输入生成器。 */
    private Deque<Supplier<TaskInput>> queuedInputs;
    /** 在等的条件；不在等时为 null。 */
    private WaitCondition waiting;
    /** 这一刻之前不检查等待的条件。 */
    private long waitNotBefore;
    /** sequence：正在跑的子目标推进器；轮到步骤之间为 null。 */
    private GoalRunner stepRunner;
    /** sequence：没做成但被允许继续的步骤结果（onFailure 为 CONTINUE 时）。 */
    private final List<TaskResult> failedSteps = new ArrayList<>();
    /** 结束后的结果；没结束时为 null。 */
    private TaskResult result;

    private GoalRunner(GoalRun run, AbilityRegistry registry, GoalRunStore store,
                       RemembersPlaces remembers, long stepBudgetTicks) {
        this.run = run;
        this.goal = run.goal();
        this.registry = registry;
        this.store = store;
        this.remembers = remembers;
        this.stepBudgetTicks = stepBudgetTicks;
    }

    /** 下达一个新目标：分配编号、记一条进行中的目标运行并立即存盘。 */
    public static GoalRunner launch(Goal goal, AbilityRegistry registry, GoalRunStore store, RemembersPlaces remembers) {
        GoalRunner runner = new GoalRunner(new GoalRun(store.nextId(), goal),
                registry, store, remembers, DEFAULT_STEP_BUDGET_TICKS);
        runner.save();
        return runner;
    }

    /**
     * 恢复一条还没结束的目标运行（重启前中断的）：恢复为暂停，明确解除暂停后才继续推进。
     * 进行中的任务不恢复——任务没法跨重启活下来，解除暂停后能力会重新看现场做决定。
     */
    public static GoalRunner resume(GoalRun restored, AbilityRegistry registry, GoalRunStore store,
                                    RemembersPlaces remembers) {
        if (!restored.unfinished()) {
            throw new IllegalStateException("目标运行 " + restored.id() + " 已经结束，不能恢复");
        }
        restored.pause();
        GoalRunner runner = new GoalRunner(restored, registry, store, remembers, DEFAULT_STEP_BUDGET_TICKS);
        runner.save();
        return runner;
    }

    /** 离线测试用：指定一步的子任务时限，其余同 launch。 */
    static GoalRunner launchWithBudget(Goal goal, AbilityRegistry registry, GoalRunStore store,
                                       RemembersPlaces remembers, long stepBudgetTicks) {
        GoalRunner runner = new GoalRunner(new GoalRun(store.nextId(), goal),
                registry, store, remembers, stepBudgetTicks);
        runner.save();
        return runner;
    }

    /** 这条推进的记录；LLM 查询目标进展时看的就是它。 */
    public GoalRun run() {
        return run;
    }

    /** LLM 回答了这条推进挂起的问题：记下回答，下一刻继续推进。 */
    public void answer(String text) {
        // sequence 自己没在等回答时，回答交给正在跑的子目标。
        if (run.question() == null && stepRunner != null && stepRunner.run().question() != null) {
            stepRunner.answer(text);
            return;
        }
        run.answer(text);
        save();
        LOG.info("目标 {} 的步骤 {} 得到回答：{}", run.id(), run.stepIndex(), text);
    }

    @Override public void start(TickContext context) {
        run.start(context.gameTick());
        save();
    }

    @Override public TickResult tick(TickContext context) {
        if (result != null) {
            return TickResult.finished(result);
        }
        // 暂停中的目标运行不推进：控制循环不该挑它，挑到了也原样放着等解除暂停。
        if (run.state() == GoalRunState.PAUSED) {
            return TickResult.RUNNING;
        }
        // 决定或任务里抛异常都不连累控制循环：转成程序错误的问题，如实收场。
        try {
            return advance(context);
        } catch (RuntimeException exception) {
            LOG.warn("目标推进时出错", exception);
            settle(TaskResult.failed("目标推进时程序出错",
                    Problem.of(Problem.Kind.INTERNAL_ERROR, exception.getClass().getSimpleName()
                            + (exception.getMessage() == null ? "" : "：" + exception.getMessage()))), context);
            return TickResult.finished(result);
        }
    }

    @Override public void pause() {
        // 被生存需求打断：正在跑的子任务松开按键、保留进度；目标本身的处境不变。
        if (child != null && !child.finished()) {
            child.pause();
        }
    }

    @Override public TaskResult close(CloseReason reason) {
        if (result != null) {
            return result;
        }
        // 还在跑的子任务先按同样的原因收尾，让它如实交代已经发生的变化。
        TaskResult fromChild = child != null && !child.finished() ? child.close(reason) : null;
        TaskResult ending = fromChild != null ? fromChild : cancelledFor(reason);
        settle(ending, null);
        return result;
    }

    @Override public Interruptibility interruptibility(TickContext context) {
        if (child == null || child.finished()) {
            // 手上没有进行中的子任务（在决定、等回答或等条件），不急的事可以趁这个空当插进来。
            return Interruptibility.BETWEEN_ACTIONS;
        }
        return child.interruptibility(context);
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

    /** 推进当前步骤的任务一刻，并按结果决定这一步怎么走。 */
    private TickResult tickChild(TickContext context) {
        // 能力钩子说任务碰到了必须由 LLM 选择的情况：暂停子任务，停下来问。
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
        TaskResult taskResult = ((TickResult.Finished) tickResult).result();
        // 钩子可以改写结果，或换成另一个决定继续这一步。
        AfterTask after = module().hooks().afterTask(stepContext(context), currentInput, taskResult);
        if (after instanceof AfterTask.Replace replace) {
            forgetChild();
            return applyDecision(replace.decision(), context);
        }
        return onStepTaskDone(((AfterTask.Accept) after).result(), context);
    }

    /** 步骤的任务有了结果：失败原样传播，成功按决定决定是重新看这一步还是直接算完成。 */
    private TickResult onStepTaskDone(TaskResult taskResult, TickContext context) {
        forgetChild();
        if (taskResult.status() != TaskResult.Status.DONE) {
            // 步骤没做成：带着完整事实结束目标。换办法重试是玩家行为层的事，不在目标推进里加。
            settle(taskResult, context);
            return TickResult.finished(result);
        }
        if (recheckAfterCurrent) {
            // 任务做成了，但这一步是否满足要回来重新看：下一刻重新问能力的决定。
            return TickResult.RUNNING;
        }
        if (queuedInputs != null && !queuedInputs.isEmpty()) {
            beginChild(queuedInputs.removeFirst().get(), context);
            return TickResult.RUNNING;
        }
        settle(taskResult, context);
        return TickResult.finished(result);
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
            settle(finish.result(), context);
            return TickResult.finished(result);
        }
        if (decision instanceof StepDecision.Run runTask) {
            module().hooks().beforeTask(stepContext(context), runTask.input());
            recheckAfterCurrent = runTask.recheckAfterSuccess();
            queuedInputs = null;
            beginChild(runTask.input(), context);
            return TickResult.RUNNING;
        }
        if (decision instanceof StepDecision.RunInOrder inOrder) {
            // 按顺序运行几个任务：先跑第一个，剩下的到轮到时才生成输入，保证时限和副作用按顺序发生。
            Deque<Supplier<TaskInput>> remaining = new ArrayDeque<>(inOrder.inputs());
            beginChild(remaining.removeFirst().get(), context);
            module().hooks().beforeTask(stepContext(context), currentInput);
            recheckAfterCurrent = false;
            queuedInputs = remaining;
            return TickResult.RUNNING;
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

    /** 开始跑当前步骤的一个任务；异常交给 tick 的兜底转成程序错误。 */
    private void beginChild(TaskInput input, TickContext context) {
        currentInput = input;
        child = new ChildTaskRunner(new ProgressTracker(1, stepBudgetTicks));
        child.begin(currentInput, registry.taskFactories(), context);
    }

    /**
     * 逐步跑 sequence 的子目标：每个子目标是一个完整的目标推进，经子任务运行器跑完再轮到下一步。
     * 某步没做成时按目标的 onFailure 决定整件事停下还是继续；都允许失败时目标以部分完成收尾。
     */
    private TickResult advanceSequence(TickContext context) {
        if (child == null || child.finished()) {
            if (run.stepIndex() >= goal.steps().size()) {
                // 步骤已经轮完却还没收尾，是调用方写错了：如实报程序错误，不悄悄当成完成。
                throw new IllegalStateException("sequence 的步骤已经轮完，却没有收尾");
            }
            beginStep(goal.steps().get(run.stepIndex()), context);
            return TickResult.RUNNING;
        }
        TickResult tickResult = child.tick(context);
        if (tickResult instanceof TickResult.Running) {
            return TickResult.RUNNING;
        }
        TaskResult stepResult = ((TickResult.Finished) tickResult).result();
        child = null;
        stepRunner = null;
        if (stepResult.status() != TaskResult.Status.DONE && goal.onFailure() == Goal.OnFailure.STOP) {
            // 这一步没做成就整件事停下：失败的事实原样作为目标的结果。
            settle(stepResult, context);
            return TickResult.finished(result);
        }
        if (stepResult.status() != TaskResult.Status.DONE) {
            failedSteps.add(stepResult);
        }
        run.advanceToStep(run.stepIndex() + 1);
        if (run.stepIndex() >= goal.steps().size()) {
            settle(sequenceResult(), context);
            return TickResult.finished(result);
        }
        save();
        beginStep(goal.steps().get(run.stepIndex()), context);
        return TickResult.RUNNING;
    }

    /** 轮到一步：这条推进中断过就接着用它自己的记录恢复（也是暂停态），否则开一条新的。 */
    private void beginStep(Goal step, TickContext context) {
        stepRunner = findUnfinishedStep(step)
                .map(restored -> {
                    restored.unpause();
                    return new GoalRunner(restored, registry, store, remembers, stepBudgetTicks);
                })
                .orElseGet(() -> new GoalRunner(new GoalRun(store.nextId(), step, run.id()),
                        registry, store, remembers, stepBudgetTicks));
        child = new ChildTaskRunner(new ProgressTracker(1, stepBudgetTicks));
        child.begin(stepRunner, context);
    }

    /** 找这条 sequence 里属于当前步骤、还没结束的记录（重启前中断的）。 */
    private Optional<GoalRun> findUnfinishedStep(Goal step) {
        return store.unfinished().stream()
                .filter(candidate -> candidate.parentRunId() == run.id())
                .filter(candidate -> candidate.goal().ability().equals(step.ability()))
                .findFirst();
    }

    /** sequence 的收尾结果：有步骤没做成就是部分完成，剩下的与第一个失败都写清楚。 */
    private TaskResult sequenceResult() {
        if (failedSteps.isEmpty()) {
            return TaskResult.done("按顺序做完了全部 " + goal.steps().size() + " 步");
        }
        TaskResult.Builder builder = TaskResult.builder(TaskResult.Status.PARTIAL,
                        goal.steps().size() - failedSteps.size() + " / " + goal.steps().size() + " 步做成")
                .problem(failedSteps.get(0).problem());
        for (TaskResult failed : failedSteps) {
            builder.remaining(failed.summary());
        }
        return builder.build();
    }

    /** 这一步的子任务已经结算完，把步骤之间的空当清出来。 */
    private void forgetChild() {
        child = null;
        currentInput = null;
        recheckAfterCurrent = false;
    }

    /** 结束目标并定下结果；记录随之存盘，之后本推进封存。 */
    private void settle(TaskResult ending, TickContext context) {
        result = ending;
        run.finish(ending, context == null ? -1 : context.gameTick());
        save();
        LOG.info("目标 {} 结束：{}", run.id(), ending.summary());
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
        };
    }

    private static TaskResult cancelledFor(CloseReason reason) {
        return TaskResult.cancelled(switch (reason) {
            case FINISHED -> "目标没有交代结果就结束了";
            case REPLACED -> "目标被新任务替换";
            case CANCELLED -> "目标被取消";
            case PLAYER_GONE -> "角色不在了，目标中止";
        });
    }
}
