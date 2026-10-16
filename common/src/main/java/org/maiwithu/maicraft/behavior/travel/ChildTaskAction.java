// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import org.maiwithu.maicraft.kernel.child.ChildTaskRunner;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 外层任务的一个阶段交给一件子任务做时，把这件子任务包成这个阶段的动作：跨过去接着走的那段、
 * 外岛回主岛的那段、建门、去要塞激活末地门，以及建门里交施工引擎砌框、交浇筑浇框。
 *
 * <p>外层被生存需求打断时它跟着暂停，走路、施工都松手，处理完从原地接着做；外层被叫先到安全处再停时转给它
 * （悬空的先落地、等传送的那一下等完）；外层提前结束（被替换、取消、角色没了）时它按取消收尾。
 * 它交代的变化、没能确认的交互、试过的办法记进外层任务的记账口。此刻能不能打断要看它手上的动作，
 * 外层任务改写可打断程度时问 {@link #interruptibility(TickContext)}。
 */
final class ChildTaskAction implements Action {
    private final ChildTaskRunner runner = new ChildTaskRunner(ChildTaskRunner.NO_LIMIT);
    private final Task task;
    private final TaskRecords records;
    private boolean started;
    private boolean settled;

    /**
     * @param task    要交给它做的子任务，还没开始
     * @param records 外层任务的记账口：子任务收场或被收尾时交代的事实并进外层这次的结果
     */
    ChildTaskAction(Task task, TaskRecords records) {
        this.task = task;
        this.records = records;
    }

    @Override public ActionStatus tick(TickContext context) {
        if (!started) {
            started = true;
            runner.begin(task, context);
            // 启动就出错的子任务当场结算：外层这一刻就拿到结果。
            if (runner.finished()) {
                return ActionStatus.done();
            }
        }
        if (runner.tick(context) instanceof TickResult.Finished) {
            return ActionStatus.done();
        }
        // 子任务这一刻有真实进展（离目的地更近、放下一块、扔出珍珠），外层也算有进展，不按"没进展"掐掉它。
        return runner.progressedNow().isPresent() ? ActionStatus.progressed() : ActionStatus.running();
    }

    /** 子任务结束了：事实并进外层，交回它的结果。动作报完成之后调一次。 */
    TaskResult settle() {
        settled = true;
        TaskResult result = runner.result();
        copyFacts(result);
        return result;
    }

    /** 此刻能不能打断看子任务的：扔出珍珠等传送、悬空搭桥、等游戏确认放方块时停不得。 */
    Interruptibility interruptibility(TickContext context) {
        return runner.interruptibility(context);
    }

    @Override public void pause() {
        if (started && !runner.finished()) {
            runner.pause();
        }
    }

    @Override public boolean stopSafely() {
        return started && runner.stopSafely();
    }

    @Override public boolean stoppedSafely() {
        return runner.stoppedSafely();
    }

    // 外层提前结束（被替换、取消、角色没了）：子任务按取消收尾，交代的事实照样并进外层的结果。
    @Override public void close() {
        if (settled) {
            return;
        }
        settled = true;
        if (started && !runner.finished()) {
            runner.close(CloseReason.CANCELLED);
        }
        if (runner.finished()) {
            copyFacts(runner.result());
        }
    }

    @Override public String describe() {
        return started ? runner.describe() : task.describe();
    }

    private void copyFacts(TaskResult result) {
        for (var change : result.changes()) {
            records.change(change);
        }
        for (var change : result.unconfirmed()) {
            records.unconfirmed(change);
        }
        for (var attempt : result.attempts()) {
            records.attempt(attempt.tried(), attempt.result());
        }
    }
}
