// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.Objects;
import java.util.function.ToIntFunction;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 在库存请求器下了单、站在取货口跟前等包裹：每秒看一眼取货口里下单后新到、装着要的东西的件数，
 * 够下单的件数就做完去取；一分钟没有新包裹、或者总共等满五分钟也做完，取已经到的。
 * 只数数和计时，不动手：到了几件由下单那一趟数好交进来，一件没到怎么交代也由它说。
 *
 * <p>什么算进展：新包裹进了取货口是看得见的真实进展，到了就重新计一分钟。没到的时候，请求界面一关，
 * 包裹在物流网络里走到哪客户端看不见，能看见的只有它进了取货口；而"一分钟没新包裹、五分钟封顶"本身就是
 * 送不过来了的判定。所以期限以内每看一眼报一次进展，什么时候不等由这里的期限说了算，拿东西任务的
 * "一分钟没进展算卡住"不抢在前面把等到一半的单掐掉、把已经到的包裹扔在取货口。等待不会因此变长：
 * 离上一件新包裹最多一分钟，总共最多五分钟。
 *
 * <p>计时按实际推进的刻数，从走到取货口开始等那一刻起算：被吃饭这类事打断的时间不算，
 * 和拿东西任务判卡住同一种算法。
 */
final class ParcelWait implements Action {

    /** 隔多久看一眼取货口（刻）。 */
    static final int LOOK_TICKS = 20;
    /** 一分钟没有新包裹就不等（刻）：链条断了、地址没人收时不在取货口前耗着；类别：玩家常识。 */
    static final int IDLE_TICKS = 20 * 60;
    /** 最多等多久（刻）：五分钟；类别：玩家常识。 */
    static final int TOTAL_TICKS = 20 * 60 * 5;

    private final int ordered;
    private final ToIntFunction<TickContext> arrivals;
    private int arrived;
    private int idleTicks;
    private int totalTicks;
    private boolean over;

    /**
     * @param ordered  下单的件数：到的够这么多就去取
     * @param arrivals 这一刻取货口里下单后新到、装着要的东西的包裹合计装着几件要的东西
     */
    ParcelWait(int ordered, ToIntFunction<TickContext> arrivals) {
        this.ordered = ordered;
        this.arrivals = Objects.requireNonNull(arrivals, "arrivals");
    }

    @Override public ActionStatus tick(TickContext context) {
        if (over) return ActionStatus.done();
        totalTicks++;
        idleTicks++;
        if (totalTicks % LOOK_TICKS != 0) return ActionStatus.running();
        // 每秒看一眼取货口：新到了包裹就重新计一分钟；够数、一分钟没新的、满五分钟都去取已经到的。
        int now = arrivals.applyAsInt(context);
        if (now > arrived) {
            arrived = now;
            idleTicks = 0;
        }
        if (arrived >= ordered || idleTicks >= IDLE_TICKS || totalTicks >= TOTAL_TICKS) {
            over = true;
            return ActionStatus.done();
        }
        // 还在期限以内：看这一眼报一次进展，判卡住交给上面的期限。
        return ActionStatus.progressed();
    }

    /** 开始等以后（连同走过来路上）到了几件要的东西。 */
    int arrived() {
        return arrived;
    }

    // 站着等包裹正是两件事之间的空当：不急的事也能插进来，回来接着等。
    @Override public Interruptibility interruptibility() {
        return Interruptibility.BETWEEN_ACTIONS;
    }

    @Override public String describe() {
        return "等包裹（到了 " + arrived + " / " + ordered + " 件）";
    }
}
