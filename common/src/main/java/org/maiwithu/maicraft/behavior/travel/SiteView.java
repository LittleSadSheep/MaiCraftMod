// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.Optional;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * 把"按格读方块"包成通行判断与方块形状判断要的 {@link BlockGetter}：
 * 选门址、勘察池岸都只在已加载区里挑，读不到的格当空气。
 */
record SiteView(Function<BlockPos, Optional<BlockState>> read) implements BlockGetter {

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return read.apply(pos).orElse(Blocks.AIR.defaultBlockState());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return 384;
    }

    @Override
    public int getMinBuildHeight() {
        return -64;
    }
}
