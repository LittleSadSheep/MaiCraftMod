// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.util;

/** Air needed to reach a known open surface, in the same air units used by the player. */
public final class SwimAirBudget {
    private static final double ASCENT_BLOCKS_PER_TICK = 0.12D;
    private static final int REACTION_TICKS = 20;
    private long lastTick = Long.MIN_VALUE;
    private int lastAir;
    private double airPerTick = 1.0D;

    public void observe(long tick, int air, boolean submerged) {
        if (!submerged || tick < lastTick) airPerTick = 1.0D;
        else if (lastTick != Long.MIN_VALUE && tick > lastTick && air < lastAir) {
            airPerTick = Math.max(airPerTick, (lastAir - (double) air) / (tick - lastTick));
        }
        lastTick = tick;
        lastAir = air;
    }

    public double airPerTick() { return airPerTick; }

    public static int requiredAirForAscent(double riseBlocks, double airPerTick) {
        if (!Double.isFinite(riseBlocks) || !Double.isFinite(airPerTick)) return Integer.MAX_VALUE;
        double ticks = Math.ceil(Math.max(0.0D, riseBlocks) / ASCENT_BLOCKS_PER_TICK) + REACTION_TICKS;
        return (int) Math.min(Integer.MAX_VALUE, Math.ceil(ticks * Math.max(1.0D, airPerTick)));
    }
}
