// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/** Read-only virtual geometry; nothing is inserted into the client or server level. */
final class PreviewWorldView implements BlockAndTintGetter {
    private final PreviewSession session;
    private final BlockAndTintGetter level;
    PreviewWorldView(PreviewSession session, BlockAndTintGetter level) { this.session = session; this.level = level; }
    @Override public BlockState getBlockState(BlockPos pos) {
        BlockState planned = session.includes(pos) ? session.cells().get(pos) : null;
        return planned != null ? planned : level.getBlockState(pos);
    }
    @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public int getHeight() { return level.getHeight(); }
    @Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
    @Override public float getShade(Direction direction, boolean shade) { return level.getShade(direction, shade); }
    @Override public LevelLightEngine getLightEngine() { return level.getLightEngine(); }
    @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) { return level.getBlockTint(pos, resolver); }
}
