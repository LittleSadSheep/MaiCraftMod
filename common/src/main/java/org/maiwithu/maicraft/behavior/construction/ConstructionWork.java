// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 施工一个阶段的活：一串靠近、备手、出手、挖这样的小动作按顺序做，做完一格记一笔。
 * 每刻只推进当前的小动作；暂停、收尾、能不能打断都交给当前那个小动作，它正在等游戏确认时不打断。
 */
abstract class ConstructionWork implements Action {

    protected final ConstructionServices services;
    protected final ConstructionSite site;
    /** 任务给的记账口：确认发生的变化、没能确认的交互、试过的办法。 */
    protected final TaskRecords records;
    private Action current;
    private String doing = "准备";
    /** 当前小动作已经做了几刻。 */
    private long currentTicks;

    ConstructionWork(ConstructionServices services, ConstructionSite site, TaskRecords records) {
        this.services = services;
        this.site = site;
        this.records = records;
    }

    /** 开始做一个小动作；做完后由 {@link #tick} 回到 {@link #advance}。 */
    protected final void begin(Action action, String what) {
        if (current != null) current.close();
        current = action;
        doing = what;
        currentTicks = 0;
    }

    /** 一个小动作最多做多少刻，到点按没做成交给 {@link #failed}；默认不限。 */
    protected long maxActionTicks() {
        return Long.MAX_VALUE;
    }

    /** 当前小动作做完了（或还没开始）时，决定下一步：开始新的小动作返回进行中，整阶段做完返回做完。 */
    protected abstract ActionStatus advance(TickContext context);

    /** 当前小动作失败了：决定怎么办（换一个办法继续，或整阶段失败）。 */
    protected abstract ActionStatus failed(TickContext context, ActionStatus.Failed failure);

    @Override public final ActionStatus tick(TickContext context) {
        if (current == null) return advance(context);
        // 一个小动作卡在原地太久（例如收临时方块时人已经不在跟前，挖掘一直"还没开始挖"）：
        // 收掉它按没做成交给本阶段换下一个，不让整个施工被外层的卡住判定掐掉。
        if (++currentTicks > maxActionTicks()) {
            String stalled = doing;
            current.close();
            current = null;
            return failed(context, new ActionStatus.Failed(Problem.of(Problem.Kind.STUCK,
                    stalled + "做了 " + maxActionTicks() + " 刻还没做成")));
        }
        ActionStatus status = current.tick(context);
        return switch (status) {
            case ActionStatus.Running running -> running;
            case ActionStatus.Done done -> {
                current.close();
                current = null;
                yield advance(context);
            }
            case ActionStatus.Failed failure -> {
                current.close();
                current = null;
                yield failed(context, failure);
            }
        };
    }

    @Override public void pause() {
        if (current != null) current.pause();
    }

    @Override public void close() {
        if (current != null) {
            current.close();
            current = null;
        }
    }

    @Override public Interruptibility interruptibility() {
        return current == null ? Interruptibility.BETWEEN_ACTIONS : current.interruptibility();
    }

    @Override public String describe() {
        return current == null ? doing : doing + "：" + current.describe();
    }
}
