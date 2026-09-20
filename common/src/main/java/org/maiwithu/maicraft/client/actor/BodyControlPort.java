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

    /** 镜头方向请求只在当前游戏刻有效，执行时根据玩家实际朝向重新计算转动。 */
    @FunctionalInterface
    interface Steering { Movement atYaw(float yaw); }

    default void applySteering(Steering steering, float currentYaw, long leaseTickRevision) {
        applyMovement(steering.atYaw(currentYaw), leaseTickRevision);
    }

    void requestLook(float yaw, float pitch, long leaseTickRevision);

    /** 当前游戏刻就要交互时，先让真实镜头射线对准目标，避免点击落到别处。 */
    default void requestImmediateLook(float yaw, float pitch, long leaseTickRevision) {
        requestLook(yaw,pitch,leaseTickRevision);
    }

    void clearLook();

    /** 立即清空自动注入的移动与按键信号；停止输入本身不发送交互包。 */
    void releaseAll();
}
