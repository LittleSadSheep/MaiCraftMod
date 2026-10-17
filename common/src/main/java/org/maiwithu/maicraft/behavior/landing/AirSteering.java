// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import org.maiwithu.maicraft.game.player.PlayerInput;

/**
 * 掉在半空时往落点那一格推：按离落点的距离和现在的水平惯性算往哪个方向按键，
 * 并估一估剩下的下落时间里推不推得过去。只给按键，不直接挪人。
 */
public final class AirSteering {

    private AirSteering() {}

    // 空中每刻按键的水平加速度是 0.02、阻力 0.91：按"四刻内把剩下的距离推完、先抵掉惯性"算想要的方向，长度不超过 1。
    private static Vec3 push(Vec3 position, Vec3 velocity, Vec3 target) {
        double x = ((target.x - position.x) * .25 - velocity.x * .91) / .02;
        double z = ((target.z - position.z) * .25 - velocity.z * .91) / .02;
        double scale = Math.max(1, Math.hypot(x, z));
        return new Vec3(x / scale, 0, z / scale);
    }

    /** 这一刻往落点推的按键：按镜头朝向折成前后、左右；潜行照传。 */
    public static PlayerInput.Movement movement(LocalPlayer player, BlockPos feet, float yaw, boolean sneak) {
        Vec3 command = push(player.position(), player.getDeltaMovement(), Vec3.atBottomCenterOf(feet));
        double angle = Math.toRadians(yaw);
        float forward = (float) (-command.x * Math.sin(angle) + command.z * Math.cos(angle));
        float strafe = (float) (command.x * Math.cos(angle) + command.z * Math.sin(angle));
        return new PlayerInput.Movement(clamp(forward), clamp(strafe), false, sneak, false);
    }

    /**
     * 从现在起一路推，落到落点那一层时离落点中心不到半格才算推得过去：最多推演两百刻，
     * 每刻查身体会不会撞到方块、经过的格子加载没有。
     */
    public static boolean reachable(LocalPlayer player, BlockPos feet) {
        double surface = feet.getY() - 1 + supportHeight(player.level(), feet.below());
        double gravity = player.getAttributeValue(Attributes.GRAVITY);
        if (!Double.isFinite(surface) || gravity <= 0 || player.getY() < surface - .01) return false;
        Vec3 position = player.position();
        Vec3 velocity = player.getDeltaMovement();
        Vec3 target = Vec3.atBottomCenterOf(feet);
        for (int tick = 0; tick < 200; tick++) {
            velocity = velocity.add(push(position, velocity, target).scale(.02));
            Vec3 next = position.add(velocity);
            AABB body = player.getBoundingBox().move(next.subtract(player.position()));
            if (next.y <= surface) body = body.move(0, surface + .001 - next.y, 0);
            for (double x : new double[] {body.minX, body.maxX}) {
                for (double z : new double[] {body.minZ, body.maxZ}) {
                    if (!player.level().isLoaded(BlockPos.containing(x, body.minY, z))) return false;
                }
            }
            if (!player.level().noCollision(player, body)) return false;
            if (next.y <= surface) return Math.hypot(next.x - target.x, next.z - target.z) < .55;
            position = next;
            velocity = new Vec3(velocity.x * .91, (velocity.y - gravity) * .98, velocity.z * .91);
        }
        return false;
    }

    /** 这一格碰撞面在身体中间那一片的最高处（半砖 0.5、满格 1）；中间一片没有碰撞面为 NaN。 */
    public static double supportHeight(BlockGetter view, BlockPos pos) {
        double highest = Double.NaN;
        var shape = view.getBlockState(pos).getCollisionShape(view, pos, CollisionContext.empty());
        for (AABB box : shape.toAabbs()) {
            if (box.maxX > 0.2 && box.minX < 0.8 && box.maxZ > 0.2 && box.minZ < 0.8) {
                highest = Double.isNaN(highest) ? box.maxY : Math.max(highest, box.maxY);
            }
        }
        return highest;
    }

    private static float clamp(float value) {
        return Math.max(-1f, Math.min(1f, value));
    }
}
