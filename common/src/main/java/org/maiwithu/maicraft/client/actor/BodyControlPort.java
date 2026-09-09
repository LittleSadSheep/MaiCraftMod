// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

/**
 * 所有走路、转头指令都通过这个接口操作同一个本地玩家。
 * 动作需要每刻续发；releaseAll 用来松开这些按键和停止转头。
 */
public interface BodyControlPort {
    // 一刻内的按键状态：前后、左右、跳跃、潜行和疾跑。两个方向量只允许 -1～1 的有限数值。
    record Movement(float forward, float strafe, boolean jumping, boolean sneaking, boolean sprinting) {
        public static final Movement STOPPED = new Movement(0.0f, 0.0f, false, false, false);

        public Movement {
            if (!Float.isFinite(forward) || forward < -1.0f || forward > 1.0f) {
                throw new IllegalArgumentException("forward must be finite and between -1 and 1");
            }
            if (!Float.isFinite(strafe) || strafe < -1.0f || strafe > 1.0f) {
                throw new IllegalArgumentException("strafe must be finite and between -1 and 1");
            }
        }
    }

    // 是否已经把玩家输入实际交给自动任务；仅提交了接管请求还不算。
    boolean automationOwnsControls();

    // 只接受当前游戏刻的指令，旧任务保存的编号不能在以后继续使用。
    void applyMovement(Movement movement, long leaseTickRevision);

    /** Re-evaluated against the physical camera yaw while its one-tick lease remains valid. */
    @FunctionalInterface
    interface Steering { Movement atYaw(float yaw); }

    default void applySteering(Steering steering, float currentYaw, long leaseTickRevision) {
        applyMovement(steering.atYaw(currentYaw), leaseTickRevision);
    }

    void requestLook(float yaw, float pitch, long leaseTickRevision);

    /** A time-critical interaction needs its real camera ray aligned during this actor tick. */
    default void requestImmediateLook(float yaw, float pitch, long leaseTickRevision) {
        requestLook(yaw,pitch,leaseTickRevision);
    }

    void clearLook();

    /** Immediately zero every injected signal. This never sends an interaction packet. */
    void releaseAll();
}
