// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;

/** 卡坑判断读的现场：从客户端世界读每一格能不能占、能不能踩、是不是水、见不见得到天。 */
final class LiveTrapCells implements TrapCheck.Cells {

    private final ClientLevel level;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    LiveTrapCells(ClientLevel level) {
        this.level = level;
    }

    // 能占：岩浆、火、仙人掌、甜浆果丛、细雪会伤人或陷人，不算；徒手能推开的门、栅栏门算（走路会开）；没有碰撞的算。
    @Override
    public boolean open(int x, int y, int z) {
        BlockState state = level.getBlockState(cursor.set(x, y, z));
        if (state.getFluidState().is(FluidTags.LAVA) || state.getBlock() instanceof BaseFireBlock
                || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)) {
            return false;
        }
        if (state.getFluidState().is(FluidTags.WATER)) return true;
        if (state.getBlock() instanceof DoorBlock door) return door.type().canOpenByHand();
        if (state.getBlock() instanceof FenceGateBlock) return true;
        return state.getCollisionShape(level, cursor).isEmpty();
    }

    // 能踩：有碰撞箱的（台阶、半砖、耕地都能站），或者是水；岩浆块、仙人掌上不站。
    @Override
    public boolean floor(int x, int y, int z) {
        BlockState state = level.getBlockState(cursor.set(x, y, z));
        if (state.getFluidState().is(FluidTags.WATER)) return true;
        if (state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)) return false;
        return !state.getCollisionShape(level, cursor).isEmpty();
    }

    @Override
    public boolean water(int x, int y, int z) {
        return level.getFluidState(cursor.set(x, y, z)).is(FluidTags.WATER);
    }

    @Override
    public boolean sky(int x, int y, int z) {
        return level.canSeeSky(cursor.set(x, y, z));
    }

    @Override
    public boolean loaded(int x, int z) {
        return level.isLoaded(cursor.set(x, level.getMinBuildHeight(), z));
    }
}
