// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.inventory;

import java.util.Optional;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 用原生投掷初速、重力和阻力挑有限个俯仰角；仅试算与转头，最终仍以真实掉落实体进入接收区为准。 */
public final class TargetedDropGeometry {
    private TargetedDropGeometry() {}

    public static AABB receiverBox(BlockPos receiver) {
        // 接收区包含目标格及很小的边缘余量；上层机器仍须检查该位置属于已审查的加工区域。
        return new AABB(receiver).inflate(0.35, 0.1, 0.35);
    }

    public static Optional<Vec3> aim(LocalPlayer player, BlockPos receiver) {
        return aimFrom(player, player.getEyePosition(), receiver);
    }

    public static boolean canReach(LocalPlayer player, Vec3 feet, BlockPos receiver) {
        // 上层选站位时只试算站立眼高，不移动或转头；真正出手前仍按角色当时的眼睛位置重新解算。
        return aimFrom(player, feet.add(0, player.getEyeHeight(net.minecraft.world.entity.Pose.STANDING), 0), receiver).isPresent();
    }

    private static Optional<Vec3> aimFrom(LocalPlayer player, Vec3 eye, BlockPos receiver) {
        if (!player.level().isLoaded(receiver)) return Optional.empty();
        Vec3 origin = eye.add(0, -0.3, 0);
        Vec3 horizontal = Vec3.atCenterOf(receiver).subtract(origin).multiply(1, 0, 1);
        double distance = horizontal.length();
        if (distance < 0.6 || distance > 6) return Optional.empty();
        // 接收区必须可见；高抛弧线越过围墙不能授权向看不到的区域投料。
        Vec3 receiverCenter = Vec3.atCenterOf(receiver);
        int sightSteps = Math.max(1, (int) Math.ceil(eye.distanceTo(receiverCenter) / 0.2));
        for (int step = 0; step < sightSteps; step++)
            if (!clear(player.level(), eye.lerp(receiverCenter, step / (double) sightSteps),
                    eye.lerp(receiverCenter, (step + 1.0) / sightSteps))) return Optional.empty();
        Vec3 forward = horizontal.normalize();
        double plane = receiver.getY() + (player.level().getFluidState(receiver).isEmpty() ? 0.1 :
                player.level().getFluidState(receiver).getHeight(player.level(), receiver));
        if (origin.y <= plane + 0.05) return Optional.empty();
        Vec3 chosen = null; double best = Double.POSITIVE_INFINITY;
        // 逐五度试俯仰角，并检查原生随机散布的边界；不允许靠撞墙、滑进未知位置或修改物品速度补救。
        for (int pitch = -55; pitch <= 70; pitch += 5) {
            double angle = Math.toRadians(pitch), score = 0;
            Vec3 nominal = forward.scale(0.3 * Math.cos(angle)).add(0, 0.1 - 0.3 * Math.sin(angle), 0);
            boolean valid = true;
            for (double lateral : new double[]{-0.02, 0.02}) for (double along : new double[]{-0.02, 0.02})
                for (double vertical : new double[]{-0.1, 0.1}) {
                Vec3 velocity = nominal.add(forward.x * along - forward.z * lateral, vertical, forward.z * along + forward.x * lateral);
                Vec3 landing = trace(player.level(), origin, velocity, receiver, plane);
                if (landing == null) { valid = false; break; }
                score = Math.max(score, landing.subtract(Vec3.atCenterOf(receiver)).horizontalDistanceSqr());
            }
            if (valid && score < best) {
                best = score; chosen = eye.add(forward.scale(Math.cos(angle)).add(0, -Math.sin(angle), 0).scale(4));
            }
        }
        return Optional.ofNullable(chosen);
    }

    private static Vec3 trace(Level level, Vec3 origin, Vec3 velocity, BlockPos receiver, double plane) {
        Vec3 at = origin;
        for (int tick = 0; tick < 40; tick++) {
            velocity = velocity.add(0, -0.04, 0); Vec3 next = at.add(velocity);
            if (next.y <= plane && at.y >= plane && velocity.y < 0) {
                Vec3 landing = at.lerp(next, (at.y - plane) / (at.y - next.y));
                AABB box = receiverBox(receiver);
                if (landing.x < box.minX + 0.125 || landing.x > box.maxX - 0.125
                        || landing.z < box.minZ + 0.125 || landing.z > box.maxZ - 0.125) return null;
                // 流体加工按实体中心所在格判断环境；外扩区仅用于寻找实体，池沿上的落地不能算进了水池。
                var requiredFluid = level.getFluidState(receiver); BlockPos actual = BlockPos.containing(landing);
                if (!requiredFluid.isEmpty() && (!level.isLoaded(actual)
                        || !requiredFluid.getType().isSame(level.getFluidState(actual).getType()))) return null;
                return clear(level, at, landing) ? landing : null;
            }
            if (!clear(level, at, next) || next.y < receiver.getY() - 1) return null;
            at = next; velocity = velocity.scale(0.98);
        }
        return null;
    }

    private static boolean clear(Level level, Vec3 from, Vec3 to) {
        // 按物品实体的实际宽高检查整段小步，池沿、顶棚和未加载格都不能被点状直线穿过。
        for (int part = 0; part <= 3; part++) {
            Vec3 point = from.lerp(to, part / 3.0);
            AABB item = new AABB(point.x - 0.125, point.y, point.z - 0.125, point.x + 0.125, point.y + 0.25, point.z + 0.125);
            for (int x = Mth.floor(item.minX); x <= Mth.floor(item.maxX); x++)
                for (int y = Mth.floor(item.minY); y <= Mth.floor(item.maxY); y++)
                    for (int z = Mth.floor(item.minZ); z <= Mth.floor(item.maxZ); z++) {
                        BlockPos cell = new BlockPos(x, y, z);
                        if (!level.isLoaded(cell)) return false;
                        for (AABB obstacle : level.getBlockState(cell).getCollisionShape(level, cell).toAabbs())
                            if (item.intersects(obstacle.move(cell))) return false;
                    }
        }
        return true;
    }
}
