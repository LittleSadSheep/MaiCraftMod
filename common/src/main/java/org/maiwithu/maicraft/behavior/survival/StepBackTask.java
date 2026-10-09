// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 退回安全处的临时任务：从深落差的边沿往后退一两格，退到就不干了。
 *
 * <p>贴边只在两个动作之间插进来，所以这一步通常很短：走到安全落点、结束，
 * 主任务从原地接着做。走不动就如实交代，不硬蹭。
 */
final class StepBackTask extends PhasedTask<StepBackTask.Phase> {

    /** 退避的阶段：走 → 到了。 */
    enum Phase { STEP }

    /** 离安全落点这么近就算退到了（格）。 */
    private static final double ARRIVED_WITHIN = 0.8;

    /** 半分钟退不到就按卡住收场。 */
    private static final long STUCK_AFTER_TICKS = 20L * 30;

    private final StepBackTask.Moves moves;
    private final TaskEventSink events;
    private final double[] safeSpot;

    /** 退怎么走：生产用走到，测试换替身。 */
    interface Moves {
        Action walkTo(double x, double y, double z);

        double[] selfPosition(TickContext context);
    }

    StepBackTask(double[] safeSpot, Moves moves, TaskEventSink events) {
        super("退离边沿", Phase.STEP, new ProgressTracker(STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.safeSpot = Objects.requireNonNull(safeSpot, "safeSpot");
        this.moves = Objects.requireNonNull(moves, "moves");
        this.events = events;
    }

    @Override protected Action enter(Phase phase) { return moves.walkTo(safeSpot[0], safeSpot[1], safeSpot[2]); }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        double[] self = moves.selfPosition(context);
        double remaining = Math.hypot(self[0] - safeSpot[0], self[2] - safeSpot[2]);
        if (remaining <= ARRIVED_WITHIN) {
            if (events != null) {
                events.publish(TaskEvent.Kind.TEMPORARY_TASK_FINISHED, "退回了安全处，继续干活");
            }
            return Next.done(TaskResult.builder(TaskResult.Status.DONE, "退离边沿，站回了安全处").build());
        }
        return runActionThen(context, () -> Next.done(TaskResult.builder(TaskResult.Status.DONE,
                "退到了安全处附近").build()));
    }

    @Override
    protected ResultDetails details() {
        return ResultDetails.NONE;
    }

    /** 走不动的失败原样上报：贴边没退成，下一刻还会再报。 */
    static Problem walkProblem(Problem cause) {
        return Problem.of(Problem.Kind.STUCK, "退不回安全处：" + cause.message());
    }
}
