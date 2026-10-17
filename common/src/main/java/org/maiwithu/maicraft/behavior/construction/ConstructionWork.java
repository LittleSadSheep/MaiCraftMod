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
 * 每刻只推进当前的小动作；暂停、先到安全处再停、收尾、能不能打断都交给当前那个小动作，它正在等游戏确认时不打断。
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
    /** 被叫先到安全处再停、手上那一下说要先收尾：这一下做完就站着等，不开始下一下；暂停时清掉。 */
    private boolean stopping;

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
        // 收尾途中上一下没做成、换了个办法接着做：新的这一下也先叫它停稳，它说现在就能停，施工也就算停稳了。
        if (stopping) action.stopSafely();
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
        // 正在收尾、手上那一下已经落地做完：站着等外层停下，接着做时再开始下一下。
        if (current == null) return stopping ? ActionStatus.running() : advance(context);
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
                yield stopping ? ActionStatus.running() : advance(context);
            }
            case ActionStatus.Failed failure -> {
                current.close();
                current = null;
                yield failed(context, failure);
            }
        };
    }

    // 被生存需求打断，或收尾完了外层暂停：不再收尾，手上那一下停下松手，回来照原样接着做。
    @Override public void pause() {
        stopping = false;
        if (current != null) current.pause();
    }

    // 被叫先到安全处再停（目标暂停或取消、不急的需求一直等不到空当）：转给手上那一下，走路悬空的先落地；
    // 它说现在就能停，或手上没有正在做的一下（两格之间），施工现在就能停。
    @Override public boolean stopSafely() {
        stopping = current != null && current.stopSafely();
        return stopping;
    }

    /** 收尾做完了：手上那一下停稳了，或已经做完、站着等。没在收尾为真。 */
    @Override public boolean stoppedSafely() {
        return !stopping || current == null || current.stoppedSafely();
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
