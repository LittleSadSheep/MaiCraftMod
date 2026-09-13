// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.Map;
import net.minecraft.world.phys.Vec3;

/** 停键后先观察自然落地和惯性消退，再固定支撑验证起点；不写位置或速度，持续不稳定时有限停工。 */
final class BuildSupportSettling {
    enum Status { WAITING, READY, FAILED }
    static final int MAX_WAIT_TICKS = 60;
    private long lastTick = Long.MIN_VALUE;
    private Vec3 previous;
    private int observedTicks, stableTicks;
    private double displacement, horizontalSpeed;
    private boolean grounded, walkingBody;
    private Status status = Status.WAITING;

    Status observe(Vec3 position, Vec3 velocity, boolean onGround, boolean ordinaryWalking, long tick) {
        if (status != Status.WAITING || tick == lastTick) return status;
        // 同一刻的多次阶段推进不能冒充连续站稳；暂停期间没观察到的刻数也不算主动等待。
        observedTicks++; grounded = onGround; walkingBody = ordinaryWalking;
        displacement = previous == null ? Double.POSITIVE_INFINITY : previous.distanceTo(position);
        horizontalSpeed = velocity.horizontalDistance();
        boolean consecutive = lastTick != Long.MIN_VALUE && tick == lastTick + 1;
        boolean settled = consecutive && onGround && ordinaryWalking && Double.isFinite(displacement + horizontalSpeed)
                && displacement <= .01 && horizontalSpeed <= .01;
        stableTicks = settled ? stableTicks + 1 : 0; previous = position; lastTick = tick;
        if (stableTicks >= 2) status = Status.READY;
        else if (observedTicks >= MAX_WAIT_TICKS) status = Status.FAILED;
        return status;
    }
    void reset() {
        lastTick = Long.MIN_VALUE; previous = null; observedTicks = stableTicks = 0;
        displacement = horizontalSpeed = 0; grounded = walkingBody = false; status = Status.WAITING;
    }
    Map<String, Object> evidence() {
        return Map.of("observed_wait_ticks", observedTicks, "stable_ticks", stableTicks, "on_ground", grounded,
                "ordinary_walking_body", walkingBody, "horizontal_speed", horizontalSpeed, "last_displacement", Double.isFinite(displacement) ? displacement : -1);
    }
}
