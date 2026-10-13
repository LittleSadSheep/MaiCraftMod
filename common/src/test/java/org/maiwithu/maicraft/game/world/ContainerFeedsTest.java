// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 进料箱、补料箱的认法：下面挂着漏斗在往下抽的是；什么都没挂、只是旁边有东西的不是。 */
class ContainerFeedsTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 替身世界：没摆的格子都是空气。 */
    private static final class World implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }

    @Test
    void 下面挂着漏斗的箱子在给机器供料() {
        World world = new World();
        BlockPos chest = new BlockPos(0, 65, 0);
        world.blocks.put(chest, Blocks.CHEST.defaultBlockState());
        world.blocks.put(chest.below(), Blocks.HOPPER.defaultBlockState());
        assertTrue(ContainerFeeds.feedsAutomation(world, chest));
    }

    @Test
    void 什么都没挂的箱子不算() {
        World world = new World();
        BlockPos chest = new BlockPos(0, 65, 0);
        world.blocks.put(chest, Blocks.CHEST.defaultBlockState());
        world.blocks.put(chest.below(), Blocks.STONE.defaultBlockState());
        world.blocks.put(chest.east(), Blocks.FURNACE.defaultBlockState());
        assertFalse(ContainerFeeds.feedsAutomation(world, chest));
    }
}
