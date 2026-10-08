// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * 世界时间规则：按一天二万四千个游戏刻划分凌晨、白天、傍晚和夜晚，并回答“现在能不能睡”。
 *
 * <p>全仓只有这一处判断可以睡的时间；相位只是状态标签，可睡窗口另按原版天空变暗公式现算，
 * 因为客户端逐刻读 {@code Level.isDay()} 会拿到加载时的旧值。
 * 判断核心是纯函数（时刻、雨量、雷量、维度是否固定时间），包一层 Level 读取；
 * 离线测试直接喂数值，不构造世界对象。
 */
public final class WorldTime {

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

    private WorldTime() {}

    /** 晴朗天气下原版可睡窗口的起点；终点 23458 由同一个天空变暗公式给出，供状态展示参考。 */
    public static final long SLEEP_WINDOW_START = 12_542L;

    public static long timeOfDay(Level level) {
        return timeOfDayOf(level.getDayTime());
    }

    /** 只取当天走到了哪一刻；总时间可能已过许多天，负时间也折回合法的一天范围内。 */
    public static long timeOfDayOf(long dayTime) {
        return Math.floorMod(dayTime, 24_000L);
    }

    public static long dayIndex(Level level) {
        return dayIndexOf(level.getDayTime());
    }

    /** 已经过了多少整天，与当天的时刻分开返回。 */
    public static long dayIndexOf(long dayTime) {
        return Math.floorDiv(dayTime, 24_000L);
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

    /** 现在是否到了能睡的时间；维度安全、怪物与床被占用仍由后续检查与原版判断。 */
    public static boolean canAttemptSleep(Level level) {
        return canSleepAt(
                level.dimensionType().hasFixedTime(),
                level.dimensionType().timeOfDay(level.getDayTime()),
                level.getRainLevel(1.0F),
                level.getThunderLevel(1.0F));
    }

    /**
     * 与原版 ServerPlayer.startSleepInBed 同判：天空变暗达到 4 之后才允许入睡（晴朗约 day_time 12542-23458）。
     * 雷暴降低天空变暗值，本身就在公式的天气因子里，不再单列雷暴条件。四段相位只是状态标签，
     * 与原版可睡窗口对不齐，不能拿相位当能不能睡的依据。
     */
    public static boolean canSleepAt(boolean fixedTime, float timeOfDayFraction, float rainLevel, float thunderLevel) {
        return !isVanillaDay(fixedTime, timeOfDayFraction, rainLevel, thunderLevel);
    }

    /** Level.isDay 的逐刻镜像：客户端 Level 的 skyDarken 字段只在世界加载与服务器 tick 内刷新，
     * 客户端逐刻读 level.isDay() 会拿到加载时的旧值（真夜被判白天），所以按原版
     * updateSkyBrightness 公式从同步的 dayTime、雨量与雷量现算同一个变暗值再比较。 */
    // Level.isDay 的逐刻镜像：客户端 Level 的 skyDarken 字段只在世界加载与服务器 tick 内刷新，
    // 逐刻读会拿到加载时的旧值（真夜被判白天），所以按原版 updateSkyBrightness 公式
    // 从同步的 dayTime、雨量与雷量现算同一个变暗值再比较。
    private static boolean isVanillaDay(boolean fixedTime, float timeOfDayFraction, float rainLevel, float thunderLevel) {
        if (fixedTime) return false;
        double rainFactor = 1.0 - (double) (rainLevel * 5.0F) / 16.0;
        double thunderFactor = 1.0 - (double) (thunderLevel * 5.0F) / 16.0;
        double dayCurve = 0.5 + 2.0 * Mth.clamp(
                (double) Mth.cos(timeOfDayFraction * (float) (Math.PI * 2)),
                -0.25, 0.25);
        return (int) ((1.0 - dayCurve * rainFactor * thunderFactor) * 11.0) < 4;
    }
}
