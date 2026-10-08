// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

/**
 * 角色的移动与视角输入入口：所有走路、按键、转头指令都通过它写进同一个本地玩家。
 *
 * <p>动作必须每刻续发：下一刻没有重新发出，输入就自动松开，旧任务残留的指令不会一直生效。
 * 主动作本刻占用准星时，随行的小动作（例如补光）只能通过辅助瞄准通道借用，不能抢主线。
 */
public interface PlayerInput {
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

    /** Baritone 的按键已使用路线朝向；移动物理会自行投影，辅助转头不能再旋转这一组按键。 */
    default void applyNavigationMovement(Movement movement, long leaseTickRevision) {
        applyMovement(movement, leaseTickRevision);
    }

    /** 镜头方向请求只在当前游戏刻有效，执行时根据玩家实际朝向重新计算转动。 */
    @FunctionalInterface
    interface Steering { Movement atYaw(float yaw); }

    default void applySteering(Steering steering, float currentYaw, long leaseTickRevision) {
        applyMovement(steering.atYaw(currentYaw), leaseTickRevision);
    }

    void requestLook(float yaw, float pitch, long leaseTickRevision);

    /** 主动作本刻未占用准星时允许随行交互；移动仍沿原路线，不能由辅助动作停步或改道。 */
    default boolean tryAuxiliaryLook(float yaw, float pitch, long leaseTickRevision) { return false; }

    /** 随行点击借用准星后必须归还；未提交动作时恢复原镜头，已提交时恢复主任务的视角目标。 */
    default void finishAuxiliaryLook(boolean submitted, long leaseTickRevision) {}

    /** 连副手准备也先检查准星归属，避免战斗瞄准期间把正在准备使用的盾换成火把。 */
    default boolean auxiliaryLookAvailable(long leaseTickRevision) { return false; }

    /** 普通导航视角在同刻让位于实际交互瞄准；下一刻未续交互请求时自动恢复路线视角。 */
    default void requestNavigationLook(float yaw, float pitch, long leaseTickRevision) {
        requestLook(yaw, pitch, leaseTickRevision);
    }

    /** 当前游戏刻就要交互时，先让真实镜头射线对准目标，避免点击落到别处。 */
    default void requestImmediateLook(float yaw, float pitch, long leaseTickRevision) {
        requestLook(yaw,pitch,leaseTickRevision);
    }

    /**
     * 多刻借用准星时按正常转头速度转向目标：只登记方向，由镜头平滑推进逐帧到位。
     * 随行补光要靠真实射线点击，不能像紧急自救那样瞬转瞬回，否则角色会看起来在闪现。
     */
    default void requestSmoothLook(float yaw, float pitch, long leaseTickRevision) {
        requestLook(yaw, pitch, leaseTickRevision);
    }

    void clearLook();

    /** 立即清空自动注入的移动与按键信号；停止输入本身不发送交互包。 */
    void releaseAll();
}
