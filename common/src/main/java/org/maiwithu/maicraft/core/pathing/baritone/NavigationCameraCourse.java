package org.maiwithu.maicraft.core.pathing.baritone;

import net.minecraft.util.Mth;

/**
 * 让普通行走视角少来回晃：偏差超过二十五度才开始转，缩到十五度以内停止，每次更新最多转九度。
 * 同一次更新多次请求都从本刻起始角度算，避免请求次数越多转得越快。
 */
final class NavigationCameraCourse {
    private boolean initialized, turning, tickStartTurning, tickStartedInitialized;
    private float yaw, tickStartYaw;
    private long tick = Long.MIN_VALUE;

    float target(float requestedYaw, long revision) {
        if (tick != revision) {
            tick = revision;
            tickStartedInitialized = initialized;
            tickStartYaw = yaw;
            tickStartTurning = turning;
        }
        if (!tickStartedInitialized) {
            yaw = Mth.wrapDegrees(requestedYaw);
            initialized = true;
            turning = false;
            return yaw;
        }
        // 始终依据本 tick 初始航向重新计算；后续目标会替换前一移动目标，而不会再额外消耗 9 度转向预算。
        float error = Mth.wrapDegrees(requestedYaw - tickStartYaw);
        turning = Math.abs(error) > 25 || (tickStartTurning && Math.abs(error) > 15);
        yaw = turning ? Mth.wrapDegrees(tickStartYaw + Mth.clamp(error, -9, 9)) : tickStartYaw;
        return yaw;
    }

    void reset() {
        initialized = false;
        turning = false;
        tick = Long.MIN_VALUE;
    }
}
