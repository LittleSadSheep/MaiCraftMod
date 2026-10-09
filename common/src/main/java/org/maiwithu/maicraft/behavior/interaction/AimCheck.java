// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 瞄准判断的纯函数：镜头转到位了没有、准星射到的是不是要处理的目标。
 * 只读几何事实给结论，不发转头指令，也不提交交互。
 */
public final class AimCheck {

    /** 实际视线与目标方向的最大夹角；差得太多时射线会点到目标旁边。 */
    static final double SETTLED_ANGLE_DEGREES = 7.0;

    /** 部件框的余量：命中点算在框的表面上时，浮点误差可能让它差一丝落在框外。 */
    private static final double PART_MARGIN = 1.0e-3;

    private AimCheck() {}

    /** 实际视线已经对准目标点（夹角不超过容差）才算转到位；发出转头指令不算。 */
    public static boolean settled(Vec3 viewVector, Vec3 eye, Vec3 aimPoint) {
        Vec3 direction = aimPoint.subtract(eye);
        if (direction.lengthSqr() < 1.0e-8) {
            return true;
        }
        return viewVector.normalize().dot(direction.normalize())
                >= Math.cos(Math.toRadians(SETTLED_ANGLE_DEGREES));
    }

    /** 准星命中了指定的那格方块。 */
    public static boolean hitsBlock(HitResult hit, BlockPos target) {
        return hit instanceof BlockHitResult blockHit
                && hit.getType() == HitResult.Type.BLOCK
                && blockHit.getBlockPos().equals(target);
    }

    /** 命中点落在部件框里（留一点余量，命中点正好在框的表面上也算）。 */
    public static boolean insidePart(BlockHitResult hit, AABB part) {
        return part.inflate(PART_MARGIN).contains(hit.getLocation());
    }

    /** 准星命中了方块上的那个部件：点到的是目标格，命中点也在部件框里；同一格上的线缆、别的面板不算。 */
    public static boolean hitsPart(HitResult hit, BlockPos target, AABB part) {
        return hitsBlock(hit, target) && insidePart((BlockHitResult) hit, part);
    }

    /** 准星命中了指定的那只实体；另一只实体走进射线不算命中。 */
    public static boolean hitsEntity(HitResult hit, Entity target) {
        return hit instanceof EntityHitResult entityHit && entityHit.getEntity() == target;
    }

    /** 射线命中者的简短描述，给换位重试的现场说明用。 */
    public static String describeHit(HitResult hit) {
        return switch (hit) {
            case BlockHitResult blockHit -> "方块 " + blockHit.getBlockPos().toShortString();
            case EntityHitResult entityHit -> "实体（编号 " + entityHit.getEntity().getId() + "）";
            default -> "空气";
        };
    }
}
