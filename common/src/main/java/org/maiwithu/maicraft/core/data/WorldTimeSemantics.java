// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.data;

import net.minecraft.util.Mth;
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

    /** 晴朗天气下原版可睡窗口的起点；终点 23458 由同一个天空变暗公式给出，这里只给回执提示用。 */
    public static final long SLEEP_WINDOW_START = 12_542L;

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
        // 与原版 ServerPlayer.startSleepInBed 同判：只在 level.isDay() 为真时拒绝入睡。
        // 雷暴降低天空变暗值，本身就在公式的天气因子里，不再单列雷暴条件；床的维度安全、
        // 怪物与占用仍由后续检查与原版判断。四段相位（isDaytime/isNighttime）只是状态标签，
        // 与原版可睡窗口（天空变暗达到 4，晴朗约 day_time 12542-23458）对不齐，不能用作门控。
        return !isVanillaDay(level);
    }

    /** Level.isDay 的逐刻镜像：客户端 Level 的 skyDarken 字段只在世界加载与服务器 tick 内刷新，
     * 客户端逐刻读 level.isDay() 会拿到加载时的旧值（真夜被判白天），所以按原版
     * updateSkyBrightness 公式从同步的 dayTime、雨量与雷量现算同一个变暗值再比较。 */
    private static boolean isVanillaDay(Level level) {
        if (level.dimensionType().hasFixedTime()) return false;
        double rainFactor = 1.0 - (double) (level.getRainLevel(1.0F) * 5.0F) / 16.0;
        double thunderFactor = 1.0 - (double) (level.getThunderLevel(1.0F) * 5.0F) / 16.0;
        double dayCurve = 0.5 + 2.0 * Mth.clamp(
                (double) Mth.cos(level.dimensionType().timeOfDay(level.getDayTime()) * (float) (Math.PI * 2)),
                -0.25, 0.25);
        return (int) ((1.0 - dayCurve * rainFactor * thunderFactor) * 11.0) < 4;
    }
}
