package org.maiwithu.maicraft.core.pathing.baritone;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Shared fall-planning and native bucket-use geometry. Only the water cell is mutated. */
public final class WaterBucketFall {
    private WaterBucketFall() {}

    public static boolean canPlace(BlockState target, BlockState support, boolean protectedTarget) {
        // A waterloggable support absorbs the bucket itself, leaving the landing cell dry.
        // Restrict the clutch to air: water placement must not replace a plant or other block.
        return !protectedTarget && target.isAir()
                && !(support.getBlock() instanceof LiquidBlockContainer);
    }

    static BlockPos waterCell(BlockHitResult hit, BlockState clicked, boolean pickup) {
        return (pickup || clicked.getBlock() instanceof LiquidBlockContainer
                ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection())).immutable();
    }

    static boolean canRecover(BlockState water, boolean placedByThisFall, boolean protectedTarget) {
        return placedByThisFall && !protectedTarget && water.is(Blocks.WATER)
                && water.getFluidState().isSource();
    }
}
