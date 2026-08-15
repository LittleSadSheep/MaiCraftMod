// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.act;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Shared block-aim policy for semantic preflight and the eventual first-person interaction. */
public final class FirstPersonInteractionTargeting {
    private static final double EPSILON = 1.0e-6D;

    private FirstPersonInteractionTargeting() {}

    /**
     * Rehearse the same block ray a converged first-person camera will cast: aim at the target
     * centre, but extend the ray through it to the full native reach. Extending matters for thin
     * outline shapes (doors, trapdoors and similar blocks) whose surface can lie just beyond the
     * centre along the approach direction.
     */
    public static boolean hasLoadedReachLine(
            Level level, Entity observer, Vec3 eye, BlockPos target, double reach) {
        if (!Double.isFinite(reach) || reach <= 0.0D
                || !level.isLoaded(BlockPos.containing(eye))
                || !level.isLoaded(target)) {
            return false;
        }
        Vec3 targetCenter = Vec3.atCenterOf(target);
        Vec3 delta = targetCenter.subtract(eye);
        double distanceSqr = delta.lengthSqr();
        if (distanceSqr < EPSILON) return true;

        Vec3 end = eye.add(delta.scale(reach / Math.sqrt(distanceSqr)));
        AABB targetCell = new AABB(target);
        if (!targetCell.contains(eye) && targetCell.clip(eye, end).isEmpty()) return false;
        BlockHitResult hit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, observer));
        return !blockedByWorld(level, eye, target, end, hit);
    }

    /**
     * The common obstruction verdict used both before travel and after the real camera converges.
     * Air aims intentionally pass through. Liquid targets use vanilla's Fluid.NONE crosshair ray,
     * so a hit beyond the liquid remains valid item-use geometry; a hit before it is a real wall.
     */
    public static boolean blockedByWorld(
            Level level, Vec3 eye, BlockPos target, Vec3 rayEnd, HitResult hit) {
        var targetState = level.getBlockState(target);
        if (targetState.isAir()) return false;
        if (targetState.getBlock() instanceof LiquidBlock) {
            AABB targetCell = new AABB(target);
            if (targetCell.contains(eye)) return false;
            var entry = targetCell.clip(eye, rayEnd);
            if (entry.isEmpty()) return true;
            if (hit == null || hit.getType() == HitResult.Type.MISS) return false;
            if (hit instanceof BlockHitResult blockHit
                    && blockHit.getBlockPos().equals(target)) return false;
            double targetDistance = eye.distanceTo(entry.orElseThrow());
            double hitDistance = eye.distanceTo(hit.getLocation());
            return hitDistance + EPSILON < targetDistance;
        }
        return !(hit instanceof BlockHitResult blockHit)
                || hit.getType() != HitResult.Type.BLOCK
                || !blockHit.getBlockPos().equals(target);
    }
}
