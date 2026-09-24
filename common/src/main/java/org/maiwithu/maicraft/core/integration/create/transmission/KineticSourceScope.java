// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.util.function.Predicate;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/** 自动发现只接受当前作业高度附近可见的出口；明确指定的已知来源仍走独立的权限与原生接口核验。 */
final class KineticSourceScope {
    static final int HEIGHT_DELTA = 4;
    private KineticSourceScope() {}

    static boolean allows(Level level, Vec3 observer, int workY, BlockPos at) {
        return !NavigationSafetyContext.protectsUse(at) && visible(level, level::isLoaded, observer, workY, at);
    }

    /** 未加载格按遮挡处理，不能透过未知区块、地板或墙壁找到另一层的私人网络。 */
    static boolean visible(BlockGetter world, Predicate<BlockPos> loaded, Vec3 observer, int workY, BlockPos at) {
        if (Math.abs((long) at.getY() - workY) > HEIGHT_DELTA || !loaded.test(at)) return false;
        BlockGetter bounded = new BlockGetter() {
            public BlockState getBlockState(BlockPos pos) { return loaded.test(pos) ? world.getBlockState(pos) : Blocks.BARRIER.defaultBlockState(); }
            public FluidState getFluidState(BlockPos pos) { return loaded.test(pos) ? world.getFluidState(pos) : Fluids.EMPTY.defaultFluidState(); }
            public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            public int getHeight() { return world.getHeight(); }
            public int getMinBuildHeight() { return world.getMinBuildHeight(); }
        };
        var hit = bounded.clip(new ClipContext(observer, Vec3.atCenterOf(at), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(at);
    }
}
