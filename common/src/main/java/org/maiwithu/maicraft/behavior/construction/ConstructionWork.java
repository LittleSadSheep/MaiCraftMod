// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

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
    }

    /** 当前小动作做完了（或还没开始）时，决定下一步：开始新的小动作返回进行中，整阶段做完返回做完。 */
    protected abstract ActionStatus advance(TickContext context);

    /** 当前小动作失败了：决定怎么办（换一个办法继续，或整阶段失败）。 */
    protected abstract ActionStatus failed(TickContext context, ActionStatus.Failed failure);

    @Override public final ActionStatus tick(TickContext context) {
        if (current == null) return advance(context);
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
