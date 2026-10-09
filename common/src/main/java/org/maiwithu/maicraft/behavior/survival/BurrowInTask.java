// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 极端自保的临时任务：露天又打不过的夜里，就地封顶，站到天亮或威胁解除。
 *
 * <p>手上有能垫的方块就往头顶放一块把天挡住；没有就站定不动硬熬——不冒进，
 * 不为了封顶跑去摸黑找材料。天亮了或威胁散了就结束，回去继续干活。
 */
final class BurrowInTask extends PhasedTask<BurrowInTask.Phase> {

    /** 自保的阶段：能封就封一下 → 站到天亮。 */
    enum Phase { SEAL, WAIT }

    /** 站着不动不算原地打转：每刻记一次进展。 */
    private static final long NEVER_STUCK = Long.MAX_VALUE;

    /** 封顶与等待的现场怎么读：还在夜里吗、手上有方块吗。 */
    interface NightStatus {
        /** 此刻还是不是该熬的夜（能睡的时段且威胁仍在）。 */
        boolean stillNight(TickContext context);

        /** 手上有没有能垫的方块。 */
        boolean holdingBlock(TickContext context);

        /** 对头顶那格放一块方块；手上没方块或交不出交互时返回 null。 */
        Action sealOverhead(TickContext context);
    }

    private final NightStatus status;
    private final TaskEventSink events;
    private Action sealing;
    private boolean sealed;

    BurrowInTask(NightStatus status, TaskEventSink events) {
        super("极端自保", Phase.SEAL, new ProgressTracker(1, NEVER_STUCK));
        this.status = Objects.requireNonNull(status, "status");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    protected Action enter(Phase phase) {
        return phase == Phase.SEAL && !sealed ? sealing : null;
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        recordProgress("自保中");
        if (!status.stillNight(context)) {
            events.publish(TaskEvent.Kind.TEMPORARY_TASK_FINISHED, "天亮了或威胁解除，从自保里出来，回去干活");
            return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                    sealed ? "封着顶熬过去了，出来继续干活" : "站到威胁解除，继续干活").build());
        }
        if (phase == Phase.SEAL) {
            if (!sealed && status.holdingBlock(context)) {
                sealing = status.sealOverhead(context);
                if (sealing != null) {
                    ActionStatus outcome = sealing.tick(context);
                    if (outcome instanceof ActionStatus.Done) {
                        sealed = true;
                        recordProgress("头顶封上了");
                    } else if (outcome instanceof ActionStatus.Failed failed) {
                        // 放不上去就不再试：站定硬熬，不为一块方块冒险。
                        events.publish(TaskEvent.Kind.NEED_UNHANDLED, "想封顶没封上：" + failed.problem().message() + "；站定硬熬");
                        sealed = true;
                    }
                }
            } else {
                sealed = true;
            }
            return Next.go(Phase.WAIT, sealed ? "封顶结束，站着等天亮" : "手上方块封不了顶，站着等天亮");
        }
        return Next.stay();
    }

    @Override
    protected ResultDetails details() {
        return ResultDetails.NONE;
    }
}
