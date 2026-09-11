// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/** Projects a sprint hop with vanilla's takeoff friction, airborne acceleration and ceiling collision. */
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
            // jumpFromGround leaves onGround true until move(), so the first tick uses
            // ground acceleration/friction. Full input bounds the actual 0.98-scaled input.
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
            // Native travel applies gravity even on the tick that hits the ceiling.
            vertical = (vertical - launch.gravity) * 0.98;
            if (height <= 0) return new JumpProjection(distance, dragSum, apex, tick);
        }
        return null;
    }
}
