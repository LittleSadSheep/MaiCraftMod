// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.timer;

import java.util.List;
import java.util.Optional;

import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 计时任务：把计时挂上、摘下，或者报一下还挂着哪些。不动角色，一刻内完成；到点叫人的事由计时表每刻自己看钟。
 *
 * <p>每个结果都附上此刻还挂着的全部计时（编号、还剩几秒、备注），LLM 不用另查一轮就知道手上压着几件事。
 */
final class TimerTask extends PhasedTask<TimerTask.Phase> {

    /** 只有一个阶段：挂上或摘下即结束。 */
    enum Phase { WRITE }

    private final TimerInput input;
    private final Timers timers;
    /** 结果细节：tick 里定下，details 原样交出去。 */
    private TimerDetails details;

    TimerTask(TimerInput input, Timers timers) {
        // 一刻内完成的小事，卡住判定只作兜底，不会真的等到。
        super("计时", Phase.WRITE, new ProgressTracker(20L, Long.MAX_VALUE));
        this.input = input;
        this.timers = timers;
    }

    /**
     * 结果细节。设下时有 timer_id、after_seconds、note；取消时 timer_id、note 是取消掉的那个；
     * pending 是做完这次之后还挂着的全部计时，先到点的排前面。
     */
    record TimerDetails(Integer timerId, Integer afterSeconds, String note, List<PendingTimer> pending)
            implements ResultDetails {
    }

    /** 一个还挂着的计时：编号、离到点还有几秒（游戏时间）、备注。 */
    record PendingTimer(int timerId, long remainingSeconds, String note) {
    }

    @Override protected Action enter(Phase phase) {
        // 挂计时是一刻内的小事，不需要动作。
        return null;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        long now = context.gameTick();
        return switch (input.operation()) {
            case SET -> set(now);
            case CANCEL -> cancel(now);
            case LIST -> list(now);
        };
    }

    // 设一个：挂满了如实拒，并列出挂着的那几个，让 LLM 等一个到点或取消一个不要的。
    private Next<Phase> set(long now) {
        Optional<Timers.Timer> timer = timers.set(input.afterSeconds(), input.note(), now);
        if (timer.isEmpty()) {
            details = new TimerDetails(null, null, null, pending(now));
            return Next.done(TaskResult.builder(TaskResult.Status.FAILED, "计时已经挂满 " + Timers.MAX_PENDING + " 个，这个没设上")
                    .problem(Problem.of(Problem.Kind.UNSUPPORTED, "同时最多挂 " + Timers.MAX_PENDING + " 个计时",
                            "等一个到点，或用 operation=cancel 按 timer_id 取消一个不要的（挂着的列在 details.pending）"))
                    .details(details)
                    .build());
        }
        Timers.Timer set = timer.get();
        details = new TimerDetails(set.id(), set.afterSeconds(), set.note(), pending(now));
        return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                        "设好了计时 " + set.id() + "：过 " + set.afterSeconds() + " 秒（游戏时间）在事件里叫你（timer_due）；角色照常干别的")
                .details(details)
                .build());
    }

    // 取消一个：编号对不上（已经到点、换过世界、写错）时如实说没有，附上还挂着的。
    private Next<Phase> cancel(long now) {
        Optional<Timers.Timer> cancelled = timers.cancel(input.timerId());
        if (cancelled.isEmpty()) {
            details = new TimerDetails(input.timerId(), null, null, pending(now));
            return Next.done(TaskResult.builder(TaskResult.Status.FAILED, "没有还挂着的计时 " + input.timerId())
                    .problem(Problem.of(Problem.Kind.NOT_FOUND, "计时 " + input.timerId()
                                    + " 不在挂着的计时里：可能已经到点叫过、换过世界被清掉，或者编号写错",
                            "挂着的列在 details.pending"))
                    .details(details)
                    .build());
        }
        Timers.Timer timer = cancelled.get();
        details = new TimerDetails(timer.id(), timer.afterSeconds(), timer.note(), pending(now));
        return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                        "取消了计时 " + timer.id() + "（离到点还有 " + timer.remainingSeconds(now) + " 秒）：" + timer.note())
                .details(details)
                .build());
    }

    private Next<Phase> list(long now) {
        List<PendingTimer> pending = pending(now);
        details = new TimerDetails(null, null, null, pending);
        return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                        pending.isEmpty() ? "没有挂着的计时" : "挂着 " + pending.size() + " 个计时")
                .details(details)
                .build());
    }

    private List<PendingTimer> pending(long now) {
        return timers.pending().stream()
                .map(timer -> new PendingTimer(timer.id(), timer.remainingSeconds(now), timer.note()))
                .toList();
    }

    @Override protected ResultDetails details() {
        return details == null ? ResultDetails.NONE : details;
    }
}
