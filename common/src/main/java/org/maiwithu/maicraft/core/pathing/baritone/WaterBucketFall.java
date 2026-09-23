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
import net.minecraft.world.level.material.WaterFluid;

/**
 * 落地放水的规则与瞄准帮助：识别水源和含水方块，找脚下可点击表面，确认一桶水实际会进入哪一格。
 */
public final class WaterBucketFall {
    private WaterBucketFall() {}

    public static boolean replaceableByWater(BlockState target) {
        return target.isAir() || target.getFluidState().isEmpty() && target.canBeReplaced(Fluids.WATER);
    }

    /** 可直接检查水源格的碰撞状态，无需假设含水实心方块已经消失。 */
    // 只为检查地形把水临时视为排掉；含水半砖等仍保留其实体外形，不把它们整个当空气。
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

    /** 第一人称水桶射线命中植物轮廓，因此水源位于其暴露顶部上方。 */
    // 脚下有会被水冲走的低矮植物时，最多向上越过两格，找实际可见的放水位置。
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

    /** 在植物轮廓附近寻找真实暴露的地面面；此操作不会破坏植物。 */
    public static BlockHitResult floorHit(BlockGetter world, BlockPos feet, Vec3 eye) {
        BlockPos floor = feet.below();
        var shape = world.getBlockState(floor).getShape(world,floor);
        if (shape.isEmpty()) return null;
        var bounds = shape.bounds();
        double[] offsets = {.5,.01,.99};
        for (double x : offsets) for (double z : offsets) {
            Vec3 point = new Vec3(floor.getX()+bounds.minX+(bounds.maxX-bounds.minX)*x,
                    floor.getY()+bounds.maxY,floor.getZ()+bounds.minZ+(bounds.maxZ-bounds.minZ)*z);
            // 选择瞄准点只需考虑局部接近方向。提交操作时仍会按真实眼位和原生交互范围进行射线检测，不要扫描高空空气。
            Vec3 from = eye.y-point.y > 4 ? point.add(eye.subtract(point).scale(4/(eye.y-point.y))) : eye;
            var hit = world.clip(new ClipContext(from,point.add(0,-.001,0),ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,CollisionContext.empty()));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(floor)) return hit;
        }
        return null;
    }

    public static boolean sourceWater(BlockState state) {
        return state.getFluidState().getType() instanceof WaterFluid
                && state.getFluidState().isSource();
    }

    public static BlockPos waterCell(BlockGetter world, BlockHitResult hit, boolean pickup) {
        return (pickup || acceptsWater(world,hit.getBlockPos())
                ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection())).immutable();
    }

    // 回收需要是本次放下的、未受保护的可舀水源；不因为已经落进水里就取得这格原有水的所有权。
    public static boolean canRecover(BlockState water, boolean placedByThisFall, boolean protectedTarget) {
        return placedByThisFall && !protectedTarget && water.getBlock() instanceof BucketPickup && sourceWater(water);
    }
}
