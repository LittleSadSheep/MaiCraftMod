// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 载具此刻的姿态与接地（纯函数）：结构自己的 +z 朝哪、绕自己的 x、z 轴斜了多少；
 * 底面压没压着地面——把每个朝下露着的碰撞盒的四个底角换到世界里，看正下方一点点是不是实心的地面。
 */
final class VehicleAttitude {

    /** 底角往下探多深（格）还算压着。 */
    static final double CONTACT_DEPTH = 0.1;

    /** 接地的三种说法。 */
    static final String GROUNDED = "grounded";
    static final String AIRBORNE = "airborne";
    static final String UNKNOWN = "unknown";

    private VehicleAttitude() {}

    /**
     * 姿态：朝向与两个斜角（度）。按"先绕世界竖轴转朝向、再绕自己的 x 轴俯仰、再绕自己的 z 轴横滚"拆。
     *
     * @param heading 结构自己的 +z 此刻朝哪：0 南、90 西、180 北、270 东（和玩家的朝向一样算）
     * @param pitch   绕自己的 x 轴斜了多少度
     * @param roll    绕自己的 z 轴斜了多少度
     */
    record Angles(double heading, double pitch, double roll) {}

    static Angles angles(SableStructures.Pose pose) {
        double[] q = pose.orientation();
        double x = q[0], y = q[1], z = q[2], w = q[3];
        double pitch = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, 2 * (w * x - y * z)))));
        double yaw = Math.toDegrees(Math.atan2(2 * (x * z + w * y), 1 - 2 * (x * x + y * y)));
        double roll = Math.toDegrees(Math.atan2(2 * (x * y + w * z), 1 - 2 * (x * x + z * z)));
        // 绕竖轴转 yaw 后自己的 +z 指向 (sin yaw, 0, cos yaw)；玩家朝向按 atan2(-x, z) 算。
        double heading = Math.toDegrees(Math.atan2(-Math.sin(Math.toRadians(yaw)), Math.cos(Math.toRadians(yaw))));
        return new Angles((heading + 360) % 360, pitch, roll);
    }

    /**
     * 接地：有一个底角正下方是实心的就是压着地面；都悬空、且探的格都加载着才说悬空；有探不到的格说不清。
     *
     * @param boxes  结构的逐格碰撞盒（地块坐标）
     * @param solidTop 世界里这一格顶面有多高（格内 0～1，没有碰撞为负）；没加载为 null
     */
    static String contact(SableStructures.Pose pose, List<AABB> boxes, Function<BlockPos, Double> solidTop) {
        Set<BlockPos> filled = new HashSet<>();
        for (AABB box : boxes) filled.add(BlockPos.containing(box.minX, box.minY, box.minZ));
        boolean unknown = false;
        for (AABB box : boxes) {
            BlockPos cell = BlockPos.containing(box.minX, box.minY, box.minZ);
            // 只看朝下露着的盒子：正下方那格还是结构自己的就不是底面。
            if (box.minY > cell.getY() + 1e-6 || filled.contains(cell.below())) continue;
            for (int corner = 0; corner < 4; corner++) {
                Vec3 local = new Vec3((corner & 1) == 0 ? box.minX : box.maxX, box.minY,
                        (corner & 2) == 0 ? box.minZ : box.maxZ);
                Vec3 world = pose.toWorld(local);
                BlockPos under = BlockPos.containing(world.x, world.y - CONTACT_DEPTH, world.z);
                Double top = solidTop.apply(under);
                if (top == null) {
                    unknown = true;
                    continue;
                }
                if (top >= 0 && under.getY() + top >= world.y - CONTACT_DEPTH) return GROUNDED;
            }
        }
        return unknown ? UNKNOWN : AIRBORNE;
    }

    /** 底面有没有泡在水里：底角正下方一点点是不是液体。 */
    static boolean onWater(SableStructures.Pose pose, List<AABB> boxes, Function<BlockPos, Boolean> liquid) {
        for (AABB box : boxes) {
            Vec3 world = pose.toWorld(new Vec3((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2));
            Boolean wet = liquid.apply(BlockPos.containing(world.x, world.y - CONTACT_DEPTH, world.z));
            if (Boolean.TRUE.equals(wet)) return true;
        }
        return false;
    }
}
