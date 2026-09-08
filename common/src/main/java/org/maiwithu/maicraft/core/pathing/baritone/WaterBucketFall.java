package org.maiwithu.maicraft.core.pathing.baritone;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;

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

    /** Find a real exposed floor face around a plant's outline; this does not destroy the plant. */
    public static BlockHitResult floorHit(BlockGetter world, BlockPos feet, Vec3 eye) {
        BlockPos floor = feet.below();
        var shape = world.getBlockState(floor).getShape(world,floor);
        if (shape.isEmpty()) return null;
        var bounds = shape.bounds();
        double[] offsets = {.5,.01,.99};
        for (double x : offsets) for (double z : offsets) {
            Vec3 point = new Vec3(floor.getX()+bounds.minX+(bounds.maxX-bounds.minX)*x,
                    floor.getY()+bounds.maxY,floor.getZ()+bounds.minZ+(bounds.maxZ-bounds.minZ)*z);
            // Only the local approach matters for choosing an aim point. Submission still
            // traces the real eye and native interaction range; do not scan high-altitude air.
            Vec3 from = eye.y-point.y > 4 ? point.add(eye.subtract(point).scale(4/(eye.y-point.y))) : eye;
            var hit = world.clip(new ClipContext(from,point.add(0,-.001,0),ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,CollisionContext.empty()));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(floor)) return hit;
        }
        return null;
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
