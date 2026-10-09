// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Attempt;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 分阶段任务：所有任务的基类。
 *
 * <p>任务的进度用一个阶段枚举表达，例如睡觉的"选床 → 拿床 → 放床 → 走到床边 → 躺下"。
 * 子类只写两件事：进入某阶段时要做什么动作（{@link #enter}），以及每刻这个阶段怎么走（{@link #tick(Enum, TickContext)}）。
 *
 * <p>下面这些每个任务都要做的事由基类统一处理，子类不再各写一遍：
 * <ul>
 *   <li>换阶段时收尾旧动作、准备新动作，并写日志；</li>
 *   <li>被生存需求打断时暂停动作，结束时收尾动作；</li>
 *   <li>停滞判定：动作报告的真实进展才算进展，<b>换阶段本身不算</b>，
 *       否则在两个阶段之间来回跳的任务永远不会被判为原地打转；</li>
 *   <li>记下运行中确认发生的变化、没能确认的交互和试过的办法，结束时组装成结果；</li>
 *   <li>被替换、被取消或角色没了时，照样如实交代已经发生了什么。</li>
 * </ul>
 *
 * <p>任务里抛出的异常不在这里捕获：由运行子任务的内核统一转成 INTERNAL_ERROR 问题。
 */
public abstract class PhasedTask<P extends Enum<P>> implements Task {
    private static final Logger LOG = LoggerFactory.getLogger(PhasedTask.class);

    /** 面板上"经过"一行最多回看几次换阶段：够看出在哪几个阶段之间来回，又不至于占满一行。 */
    private static final int RECENT_PHASE_CHANGES = 5;

    private final String label;
    private final ProgressTracker progress;
    private final List<Change> changes = new ArrayList<>();
    private final List<Change> unconfirmed = new ArrayList<>();
    private final List<Attempt> attempts = new ArrayList<>();
    /** 最近几次换阶段，先发生的在前；只给面板看，不参与判断。 */
    private final Deque<TaskProgress.PhaseChange> recentPhases = new ArrayDeque<>();
    private P phase;
    private Action action;
    private boolean entered;
    private TaskResult result;

    /**
     * @param label      这件事的名字，用于面板、日志和结果，例如"睡觉"
     * @param firstPhase 第一个阶段
     * @param progress   本任务的进度跟踪；多久没进展算卡住、最多做多久，由能力按自己的节奏决定
     */
    protected PhasedTask(String label, P firstPhase, ProgressTracker progress) {
        this.label = Objects.requireNonNull(label, "label");
        this.phase = Objects.requireNonNull(firstPhase, "firstPhase");
        this.progress = Objects.requireNonNull(progress, "progress");
    }

    /** 进入某个阶段时调用一次：返回这个阶段要做的动作；只做判断、不需要动作的阶段返回 null。 */
    protected abstract Action enter(P phase);

    /** 每刻推进当前阶段：留在原阶段、换阶段、完成或失败。 */
    protected abstract Next<P> tick(P phase, TickContext context);

    /** 本能力特有的结果细节；默认没有。 */
    protected ResultDetails details() {
        return ResultDetails.NONE;
    }

    /** 没完成就结束时，还剩哪些部分没做；默认不列。 */
    protected List<String> remaining() {
        return List.of();
    }

    /** 阶段的中文名，用于面板与日志；默认用枚举名。 */
    protected String describePhase(P value) {
        return value.name();
    }

    /** 换阶段之后调用，例如发一条任务进度事件；默认什么都不做。 */
    protected void onPhaseChange(P from, P to, String why) {}

    /** 当前阶段。 */
    protected final P phase() {
        return phase;
    }

    /** 当前阶段的动作；只做判断的阶段为 null。 */
    protected final Action action() {
        return action;
    }

    /** 推进当前动作一刻；动作报告的真实进展会自动记进进度跟踪。 */
    protected final ActionStatus runAction(TickContext context) {
        if (action == null) {
            throw new IllegalStateException(label + " 的阶段 " + phase + " 没有动作，不能调用 runAction()");
        }
        ActionStatus status = action.tick(context);
        if (status instanceof ActionStatus.Running running && running.progressed()
                || status instanceof ActionStatus.Done) {
            progress.recordProgress(action.describe());
        }
        return status;
    }

    /**
     * 推进当前动作一刻，并按结果决定走向：还在做就留在本阶段；失败就把问题原样上报；做完了才执行给定的走向。
     * 例：{@code case APPROACH -> runActionThen(context, () -> Next.go(Phase.LIE_DOWN, "到床边了"));}
     */
    protected final Next<P> runActionThen(TickContext context, Supplier<Next<P>> whenDone) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> whenDone.get();
            case ActionStatus.Failed failed -> Next.fail(failed.problem());
        };
    }

    /** 记一次不经过动作的真实进展，例如判断阶段确认选中了一张能用的床。 */
    protected final void recordProgress(String what) {
        progress.recordProgress(what);
    }

    /** 记下一条已确认发生的变化，结束时进入结果的 changes。 */
    protected final void recordChange(Change change) {
        changes.add(Objects.requireNonNull(change, "change"));
    }

    /** 记下一条已提交、但没能确认结果的交互，结束时进入结果的 unconfirmed；这类交互不能盲目重做。 */
    protected final void recordUnconfirmed(Change change) {
        unconfirmed.add(Objects.requireNonNull(change, "change"));
    }

    /** 记下一次试过的办法及结果，结束时进入结果的 attempts。 */
    protected final void recordAttempt(String tried, String whatHappened) {
        attempts.add(new Attempt(tried, whatHappened));
    }

    @Override
    public final void start(TickContext context) {
        enterIfNeeded();
    }

    @Override
    public final TickResult tick(TickContext context) {
        if (result != null) return TickResult.finished(result);
        enterIfNeeded();
        progress.tick();
        TickResult tickResult = apply(tick(phase, context));
        if (tickResult instanceof TickResult.Running) {
            // 先让本刻的动作有机会报告进展，再判断是否原地打转。
            ProgressTracker.Status status = progress.status();
            if (status instanceof ProgressTracker.Status.Stuck stuck) {
                return finish(failedResult(Problem.of(Problem.Kind.STUCK,
                        "自「" + stuck.lastProgress() + "」之后推进了 " + stuck.idleTicks() + " 刻都没有新进展")));
            }
            if (status instanceof ProgressTracker.Status.TimedOut timedOut) {
                return finish(failedResult(Problem.of(Problem.Kind.STUCK,
                        "已推进 " + timedOut.activeTicks() + " 刻，超过这件事最多能做的时长")));
            }
        }
        return tickResult;
    }

    @Override
    public final void pause() {
        if (action != null) action.pause();
    }

    @Override
    public final TaskResult close(CloseReason reason) {
        closeAction();
        if (result != null) return result;
        // 没走到结局就结束：照样交代已经发生的变化和没做完的部分，不能当作什么都没发生。
        String why = switch (reason) {
            case FINISHED -> "结束";
            case REPLACED -> "被新的任务替换";
            case CANCELLED -> "被取消";
            case PLAYER_GONE -> "角色没了（死亡、断线或换世界）";
        };
        result = TaskResult.builder(TaskResult.Status.CANCELLED, label + "：" + why)
                .changes(changes).unconfirmed(unconfirmed).attempts(attempts)
                .remaining(remaining()).details(details()).build();
        return result;
    }

    @Override
    public Interruptibility interruptibility(TickContext context) {
        // 没有进行中的动作，说明正处在两个动作之间，不急的事可以趁这个空当插进来。
        return action == null ? Interruptibility.BETWEEN_ACTIONS : action.interruptibility();
    }

    /** 此刻在哪个阶段、走过哪几段、多久没进展；任务已经结束时为空。 */
    @Override
    public final Optional<TaskProgress> currentProgress() {
        if (result != null) return Optional.empty();
        return Optional.of(new TaskProgress(describe(), describePhase(phase), List.copyOf(recentPhases),
                progress.lastProgress(), progress.ticksSinceProgress(), progress.stuckAfterTicks(),
                progress.activeTicks(), progress.maxTicks()));
    }

    @Override
    public final String describe() {
        String text = label + "：" + describePhase(phase);
        return action == null ? text : text + "，" + action.describe();
    }

    private void enterIfNeeded() {
        if (entered) return;
        entered = true;
        action = enter(phase);
    }

    private TickResult apply(Next<P> next) {
        return switch (next) {
            case Next.Stay<P> stay -> TickResult.RUNNING;
            case Next.Go<P> go -> {
                P from = phase;
                closeAction();
                phase = go.phase();
                entered = false;
                enterIfNeeded();
                LOG.debug("{}：{} → {}（{}）", label, from, phase, go.why());
                // 换阶段记一笔给面板：在两个阶段之间来回跳时，一眼就能从"经过"里看出来。
                if (recentPhases.size() == RECENT_PHASE_CHANGES) recentPhases.removeFirst();
                recentPhases.addLast(new TaskProgress.PhaseChange(describePhase(from), describePhase(phase), go.why()));
                onPhaseChange(from, phase, go.why());
                yield TickResult.RUNNING;
            }
            case Next.Done<P> done -> finish(merge(done.result()));
            case Next.Fail<P> fail -> finish(failedResult(fail.problem()));
        };
    }

    // 子类在 Next.done 里只需写结论与细节；运行中用 recordChange() 等记下的内容在这里补进结果。
    private TaskResult merge(TaskResult given) {
        TaskResult.Builder builder = given.toBuilder()
                .changes(changes).unconfirmed(unconfirmed).attempts(attempts);
        if (given.status() != TaskResult.Status.DONE && given.remaining().isEmpty()) {
            builder.remaining(remaining());
        }
        if (given.details() == ResultDetails.NONE) builder.details(details());
        return builder.build();
    }

    private TaskResult failedResult(Problem problem) {
        return TaskResult.builder(TaskResult.Status.FAILED, label + "没有完成：" + problem.message())
                .problem(problem).changes(changes).unconfirmed(unconfirmed).attempts(attempts)
                .remaining(remaining()).details(details()).build();
    }

    private TickResult finish(TaskResult value) {
        closeAction();
        result = value;
        return TickResult.finished(value);
    }

    private void closeAction() {
        if (action != null) {
            action.close();
            action = null;
        }
    }
}
