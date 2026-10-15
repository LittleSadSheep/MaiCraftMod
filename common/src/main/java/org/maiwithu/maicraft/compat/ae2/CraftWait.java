// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

/**
 * 在网络里下了单、站在终端前等成品的期限。纯判断，不读世界也不动手。
 *
 * <p>成品一进网络取货动作就取走，取到了就重新计时：两分钟没再多出来就不等了，总共最多等五分钟——再长就该先去干别的，
 * 作业留在网络里接着做，回头再来取。AE2 报角色自己这一单已经结束（做完或被取消），过两秒终端里的存货同步过来还是没有更多，
 * 也不再等：再等也不会有了。
 */
final class CraftWait {

    /** 成品多久没再多出来就不等（刻）：加工样板的第一批要过熔炉、机器，给两分钟；类别：玩家常识。 */
    static final int IDLE_TICKS = 20 * 120;
    /** 下单后最多等多久（刻）：五分钟，和库存请求器等包裹同一种口径；类别：玩家常识。 */
    static final int TOTAL_TICKS = 20 * 300;
    /** AE2 报这单结束后，等终端里的存货同步过来再下结论的宽限（刻）。 */
    static final int ENDED_SETTLE_TICKS = 40;

    /** 这一刻的结论。 */
    enum Verdict {
        /** 接着等。 */
        WAIT,
        /** 两分钟没再出新的成品。 */
        IDLE,
        /** 等满了五分钟。 */
        TOO_LONG,
        /** AE2 报这单已经结束，网络里也没有更多了。 */
        ENDED
    }

    private final int takenAtStart;
    private int lastTaken;
    private int idleTicks;
    private int totalTicks;
    private int endedTicks;
    /** 见过 AE2 记着这一单：之后它不记了才算结束；刚下单那一两刻服务端的"开工"还没到，不当成结束。 */
    private boolean pendingSeen;

    /**
     * @param takenAtStart 开始等的时候这次取货已经取到几件（先取走的现货）；到了几件从这里往上数
     * @param pending      此刻 AE2 是否记着这一单
     */
    CraftWait(int takenAtStart, boolean pending) {
        this.takenAtStart = takenAtStart;
        this.lastTaken = takenAtStart;
        this.pendingSeen = pending;
    }

    /**
     * 又等了一刻。
     *
     * @param taken   这次取货此刻一共取到几件；比上一刻多就是又进来一批成品，重新计时
     * @param pending AE2 此刻还记不记着这一单
     */
    Verdict tick(int taken, boolean pending) {
        totalTicks++;
        if (taken > lastTaken) {
            lastTaken = taken;
            idleTicks = 0;
            endedTicks = 0;
        } else {
            idleTicks++;
        }
        if (pending) {
            pendingSeen = true;
            endedTicks = 0;
        } else if (pendingSeen && ++endedTicks > ENDED_SETTLE_TICKS) {
            return Verdict.ENDED;
        }
        if (idleTicks >= IDLE_TICKS) return Verdict.IDLE;
        if (totalTicks >= TOTAL_TICKS) return Verdict.TOO_LONG;
        return Verdict.WAIT;
    }

    /** 开始等以后到了几件。 */
    int arrived() {
        return lastTaken - takenAtStart;
    }
}
