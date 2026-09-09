// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.util;

/**
 * 按要上浮多少格和最近观察到的耗气速度，预留够浮出水面的氧气，再多留三秒反应时间。
 */
public final class SwimAirBudget {
    private static final double ASCENT_BLOCKS_PER_TICK = 0.12D;
    /** Keep three seconds beyond the ascent estimate for an obstruction, hit or delayed input. */
    private static final int REACTION_TICKS = 60;
    private long lastTick = Long.MIN_VALUE;
    private int lastAir;
    private double airPerTick = 1.0D;

    // 同一次潜水只提高耗气估计，不因某几刻没有扣气就放松；眼睛出水或游戏时间倒退时恢复基础估计。
    public void observe(long tick, int air, boolean submerged) {
        if (!submerged || tick < lastTick) airPerTick = 1.0D;
        else if (lastTick != Long.MIN_VALUE && tick > lastTick && air < lastAir) {
            airPerTick = Math.max(airPerTick, (lastAir - (double) air) / (tick - lastTick));
        }
        lastTick = tick;
        lastAir = air;
    }

    public double airPerTick() { return airPerTick; }

    // 按每刻上升 0.12 格估算时间；深度或耗气速度无法计算时返回极大值，不当作不需要氧气。
    public static int requiredAirForAscent(double riseBlocks, double airPerTick) {
        if (!Double.isFinite(riseBlocks) || !Double.isFinite(airPerTick)) return Integer.MAX_VALUE;
        double ticks = Math.ceil(Math.max(0.0D, riseBlocks) / ASCENT_BLOCKS_PER_TICK) + REACTION_TICKS;
        return (int) Math.min(Integer.MAX_VALUE, Math.ceil(ticks * Math.max(1.0D, airPerTick)));
    }
}
