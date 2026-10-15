// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;

/**
 * 路线到不了目标、引擎只给"往那边最好走到哪"的半截路时，看人有没有离目标更近。
 * 半截路走了又算、算了又走，半分钟没比之前最近的一次更近两格，就当这条路走不通，交还上层换个去处——
 * 像人绕着一片岩浆海找不到过去的路，转一会儿就换个方向，不在原地来回挪。
 * 实机 10-15 下界岩浆海边探索，引擎一直给五到七格的半截路，人在原地来回挪了四分钟（身子一直在动，原先的卡住判断不管）。
 */
final class PartialRouteWatch {

    /** 半截路上这么久没更近就放弃。 */
    static final int LIMIT_TICKS = 600;
    /** 更近两格才算更近：寻路估价一格约 3.6。 */
    static final double MIN_GAIN = 2 * NavGoal.COST_HEURISTIC;

    private double best = Double.MAX_VALUE;
    private long bestTick = Long.MIN_VALUE;

    /**
     * 走在半截路上的一刻：比最近的一次更近就重新起算，否则看过了多久。
     *
     * @param remaining 此刻离目标的寻路估价
     * @return 半截路上已经这么久没更近
     */
    boolean stalled(double remaining, long tick) {
        if (bestTick == Long.MIN_VALUE || tick < bestTick || remaining < best - MIN_GAIN) {
            best = Math.min(best, remaining);
            bestTick = tick;
            return false;
        }
        return tick - bestTick > LIMIT_TICKS;
    }

    /** 有了一条能到目标的整路、或换了目标：从头算。 */
    void reset() {
        best = Double.MAX_VALUE;
        bestTick = Long.MIN_VALUE;
    }
}
