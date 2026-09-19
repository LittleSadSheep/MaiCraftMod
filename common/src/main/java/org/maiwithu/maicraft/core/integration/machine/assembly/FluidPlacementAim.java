// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.act.FirstPersonInteractionTargeting;

/** 为源格寻找真实桶能命中的支撑面；不要求射线穿过空气格中心后还够得到更远的墙。 */
public final class FluidPlacementAim {
    private FluidPlacementAim() {}
    public static BlockHitResult find(Level level, Entity observer, Vec3 eye, BlockPos target, double reach, Item bucket) {
        if (!Double.isFinite(reach) || reach <= 0 || !level.isLoaded(target) || !level.isLoaded(BlockPos.containing(eye))) return null;
        BlockHitResult best = FirstPersonInteractionTargeting.visibleBucketHit(level, observer, eye, target, reach, bucket);
        double distance = best == null ? Double.POSITIVE_INFINITY : best.getLocation().distanceToSqr(eye);
        for (Direction side : Direction.values()) {
            BlockPos support = target.relative(side);
            if (!level.isLoaded(support) || level.getBlockState(support).isAir()) continue;
            // 瞄支撑块朝向源格的面心并略向块内收，避免恰落浮点边界；最终仍按原版射线核对真实落格。
            Vec3 aim = Vec3.atCenterOf(support).add(-side.getStepX() * .499, -side.getStepY() * .499, -side.getStepZ() * .499);
            Vec3 direction = aim.subtract(eye); if (direction.lengthSqr() < 1e-10) continue;
            var hit = FirstPersonInteractionTargeting.bucketRay(level, observer, eye, eye.add(direction.normalize().scale(reach)), bucket);
            if (!FirstPersonInteractionTargeting.acceptsBucketHit(level, target, bucket, hit)) continue;
            double candidate = hit.getLocation().distanceToSqr(eye);
            if (candidate < distance) { distance = candidate; best = hit; }
        }
        return best;
    }
}
