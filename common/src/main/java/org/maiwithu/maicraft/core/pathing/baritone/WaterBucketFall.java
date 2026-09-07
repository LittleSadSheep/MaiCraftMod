package org.maiwithu.maicraft.core.pathing.baritone;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;

/** Shared fall-planning and native bucket-use geometry, including replaceable plants and waterlogging. */
public final class WaterBucketFall {
    private WaterBucketFall() {}

    public static boolean replaceableByWater(BlockState target) {
        return target.isAir() || target.getFluidState().isEmpty() && target.canBeReplaced(Fluids.WATER);
    }

    /** Source-water collision can be checked without pretending a waterlogged solid disappeared. */
    public static BlockState dryGeometry(BlockState state) {
        if (state.is(Blocks.WATER)) return Blocks.AIR.defaultBlockState();
        return state.hasProperty(BlockStateProperties.WATERLOGGED)
                ? state.setValue(BlockStateProperties.WATERLOGGED,false) : state;
    }

    public static boolean waterContainer(BlockState state) {
        return state.getBlock() instanceof LiquidBlockContainer && state.hasProperty(BlockStateProperties.WATERLOGGED);
    }

    public static boolean canWaterlog(BlockGetter world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return waterContainer(state) && state.getFluidState().isEmpty()
                && acceptsWater(world,pos);
    }

    public static boolean acceptsWater(BlockGetter world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof LiquidBlockContainer container
                && container.canPlaceLiquid(null,world,pos,state,Fluids.WATER);
    }

    /** The POV bucket ray hits a plant's outline, so the source is above its exposed top. */
    public static BlockPos exposedWaterCell(BlockGetter world, BlockPos feet) {
        BlockPos source = feet;
        for (int blocks=0;blocks<2;blocks++) {
            BlockState state = world.getBlockState(source);
            if (state.isAir() || !replaceableByWater(state)
                    || !state.getCollisionShape(world,source).isEmpty()
                    || state.getShape(world,source).isEmpty()) break;
            source = source.above();
        }
        return source;
    }

    public static boolean sourceWater(BlockState state) {
        return state.getFluidState().getType() instanceof net.minecraft.world.level.material.WaterFluid
                && state.getFluidState().isSource();
    }

    public static BlockPos waterCell(BlockGetter world, BlockHitResult hit, boolean pickup) {
        return (pickup || acceptsWater(world,hit.getBlockPos())
                ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection())).immutable();
    }

    public static boolean canRecover(BlockState water, boolean placedByThisFall, boolean protectedTarget) {
        return placedByThisFall && !protectedTarget && water.getBlock() instanceof BucketPickup && sourceWater(water);
    }
}
