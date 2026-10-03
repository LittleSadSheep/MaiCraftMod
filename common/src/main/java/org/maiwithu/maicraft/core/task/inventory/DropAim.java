// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.inventory;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.task.ActualViewConvergenceGate;

/** 停步后朝附近最开阔的远处微微抬头，实际镜头对齐后才允许背包投掷。 */
final class DropAim {
    private final ActualViewConvergenceGate convergence = new ActualViewConvergenceGate();
    private Vec3 origin;
    private float yaw;
    private Float fixedYaw;
    private static final float PITCH = -15;

    void direction(Direction direction) {
        // 丢弃侧袋经过通道检查后固定投掷方向，不能又按远处视线把垃圾转投回唯一走廊。
        fixedYaw = direction.toYRot(); origin = null; convergence.reset();
    }

    boolean ready(LocalPlayerContext context) {
        LocalPlayer player = context.player();
        context.body().applyMovement(BodyControlPort.Movement.STOPPED, context.tickRevision());
        // 被外力推走后重新选择方向；观察只决定往哪里看，不修改掉落物速度，也不替代原生碰撞结算。
        if (origin == null || origin.distanceToSqr(player.position()) > .04) {
            origin = player.position(); yaw = player.getYRot(); double best = -1;
            for (int turn = 0; turn < (fixedYaw == null ? 16 : 1); turn++) {
                float candidate = fixedYaw == null ? player.getYRot() + (turn % 2 == 0 ? turn / 2 : -(turn + 1) / 2) * 22.5F : fixedYaw;
                Vec3 from = player.getEyePosition().add(0, -.3, 0), to = from.add(Vec3.directionFromRotation(PITCH, candidate).scale(4));
                double clearance = player.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, player)).getLocation().distanceToSqr(from);
                if (clearance > best + .01) { best = clearance; yaw = candidate; }
            }
            convergence.reset();
        }
        context.body().requestLook(yaw, PITCH, context.tickRevision());
        return convergence.ready(player, Vec3.directionFromRotation(PITCH, yaw))
                && player.getDeltaMovement().horizontalDistanceSqr() < .0025;
    }
}
