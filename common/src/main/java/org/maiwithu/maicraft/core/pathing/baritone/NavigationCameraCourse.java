package org.maiwithu.maicraft.core.pathing.baritone;

import net.minecraft.util.Mth;

/** One course turn budget per actor tick, even when multiple movements finish in that tick. */
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
        // Recompute from the tick's initial course: a later target replaces an earlier
        // movement's target instead of spending another 9 degrees of angular budget.
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
