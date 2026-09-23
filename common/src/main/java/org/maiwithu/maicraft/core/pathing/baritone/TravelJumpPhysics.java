// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/** 按原版起跳摩擦、空中加速和顶棚碰撞规则预测疾跑跳跃轨迹。 */
final class TravelJumpPhysics {
    private TravelJumpPhysics() {}

    record Launch(double gravity, double jumpSpeed, double forwardSpeed,
                  double groundAcceleration, double groundDrag, double bodyHeight) {}
    record JumpProjection(double forwardDistance, double airDragSum, double apexHeight, int airborneTicks) {}

    static Launch capture(LocalPlayer player, Vec3 heading) {
        var body = player.level().getBlockState(player.blockPosition()).getBlock();
        var support = player.level().getBlockState(player.getBlockPosBelowThatAffectsMyMovement()).getBlock();
        double jumpFactor = body.getJumpFactor() == 1 ? support.getJumpFactor() : body.getJumpFactor();
        double jump = player.getAttributeValue(Attributes.JUMP_STRENGTH) * jumpFactor + player.getJumpBoostPower();
        double speed = player.getAttributeValue(Attributes.MOVEMENT_SPEED) * (player.isSprinting() ? 1 : 1.3);
        double friction = support.getFriction();
        return new Launch(player.getAttributeValue(Attributes.GRAVITY), jump,
                player.getDeltaMovement().dot(heading) + 0.2,
                speed * 0.21600002 / (friction * friction * friction), friction * 0.91,
                player.getBbHeight());
    }

    static JumpProjection project(Launch launch, boolean headHit) {
        if (!Double.isFinite(launch.gravity + launch.jumpSpeed + launch.forwardSpeed
                + launch.groundAcceleration + launch.groundDrag + launch.bodyHeight)
                || launch.gravity <= 0 || launch.jumpSpeed <= 0 || launch.forwardSpeed < 0
                || launch.groundAcceleration < 0 || launch.groundDrag <= 0 || launch.groundDrag > 1
                || launch.bodyHeight <= 0 || headHit && launch.bodyHeight >= 2) return null;
        double height = 0, apex = 0, distance = 0, dragSum = 0, drag = 1;
        double vertical = launch.jumpSpeed, forward = launch.forwardSpeed;
        for (int tick = 1; tick <= 40; tick++) {
            // jumpFromGround 会将 onGround 保持为 true，直到 move() 执行，因此第一刻仍使用地面加速和摩擦；完整输入幅度用于界定实际 0.98 缩放后的输入。
            forward += tick == 1 ? launch.groundAcceleration : 0.026;
            distance += forward;
            dragSum += drag;
            double friction = tick == 1 ? launch.groundDrag : 0.91;
            forward *= friction;
            drag *= friction;
            height += vertical;
            if (headHit && height > 2 - launch.bodyHeight) {
                height = 2 - launch.bodyHeight;
                vertical = 0;
            }
            apex = Math.max(apex, height);
            // 原生移动会在撞到顶棚的同一 tick 继续施加重力。
            vertical = (vertical - launch.gravity) * 0.98;
            if (height <= 0) return new JumpProjection(distance, dragSum, apex, tick);
        }
        return null;
    }
}
