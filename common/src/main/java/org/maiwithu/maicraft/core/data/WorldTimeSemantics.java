// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.data;

import net.minecraft.world.level.Level;

/** One authoritative interpretation of the vanilla 24,000-tick day clock. */
public final class WorldTimeSemantics {

    public enum Phase {
        DAWN("dawn"),
        DAY("day"),
        DUSK("dusk"),
        NIGHT("night");

        private final String id;

        Phase(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    private WorldTimeSemantics() {}

    public static long timeOfDay(Level level) {
        return Math.floorMod(level.getDayTime(), 24_000L);
    }

    public static long dayIndex(Level level) {
        return Math.floorDiv(level.getDayTime(), 24_000L);
    }

    public static Phase phase(Level level) {
        return phase(level.getDayTime());
    }

    public static Phase phase(long dayTime) {
        long time = Math.floorMod(dayTime, 24_000L);
        if (time <= 999L || time >= 23_000L) return Phase.DAWN;
        if (time <= 11_999L) return Phase.DAY;
        if (time <= 12_999L) return Phase.DUSK;
        return Phase.NIGHT;
    }

    public static boolean isDaytime(Level level) {
        return phase(level) != Phase.NIGHT;
    }

    public static boolean isNighttime(Level level) {
        return phase(level) == Phase.NIGHT;
    }

    public static boolean canAttemptSleep(Level level) {
        return level.isThundering() || isNighttime(level);
    }
}
