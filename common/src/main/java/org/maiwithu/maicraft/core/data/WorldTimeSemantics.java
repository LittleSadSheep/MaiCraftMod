// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.data;

import net.minecraft.world.level.Level;

/** 按一天二万四千个游戏刻划分凌晨、白天、傍晚和夜晚，供状态显示与等待任务使用。 */
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
        // 总时间可能已过许多天，这里只取当天走到了哪一刻；负时间也会落在合法的一天范围内。
        return Math.floorMod(level.getDayTime(), 24_000L);
    }

    public static long dayIndex(Level level) {
        // 计算已经过了多少整天，与当天的时刻分开返回。
        return Math.floorDiv(level.getDayTime(), 24_000L);
    }

    public static Phase phase(Level level) {
        return phase(level.getDayTime());
    }

    public static Phase phase(long dayTime) {
        // 这里用固定时刻区分四段，不根据洞穴亮度、天气或维度的天空效果推测昼夜。
        long time = Math.floorMod(dayTime, 24_000L);
        if (time <= 999L || time >= 23_000L) return Phase.DAWN;
        if (time <= 11_999L) return Phase.DAY;
        if (time <= 12_999L) return Phase.DUSK;
        return Phase.NIGHT;
    }

    public static boolean isDaytime(Level level) {
        // 对“等天亮”的判断，凌晨和傍晚也算非夜间；只有 NIGHT 返回 false。
        return phase(level) != Phase.NIGHT;
    }

    public static boolean isNighttime(Level level) {
        return phase(level) == Phase.NIGHT;
    }

    public static boolean canAttemptSleep(Level level) {
        // 这里只判断雷暴或夜间；睡觉规划和任务另查床的维度安全性，怪物与占用仍由原版判断。
        return level.isThundering() || isNighttime(level);
    }
}
