package org.maiwithu.maicraft.core.task.build;

/**
 * 检查瞄准误差是否还在缩小：连续四十个游戏刻没有累计改善超过 0.1 度，就认为这次瞄准停滞。
 * 同一刻反复调用不增加次数；中断一刻以上或时间倒退后，重新开始计数。
 */
final class BuildAimProgress {
    private long lastTick = Long.MIN_VALUE;
    private double bestError = Double.POSITIVE_INFINITY;
    private int idleTicks;

    boolean stalled(long tick, double errorDegrees) {
        if (lastTick == Long.MIN_VALUE || tick < lastTick || tick - lastTick > 1) {
            bestError = errorDegrees; idleTicks = 0;
        } else if (tick != lastTick) {
            if (errorDegrees < bestError - .1) { bestError = errorDegrees; idleTicks = 0; }
            else idleTicks++;
        }
        lastTick = tick;
        return idleTicks >= 40;
    }

    void reset() { lastTick = Long.MIN_VALUE; bestError = Double.POSITIVE_INFINITY; idleTicks = 0; }
}
