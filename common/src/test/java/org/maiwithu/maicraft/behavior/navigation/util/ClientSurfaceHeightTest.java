// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.util;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 检查清树选址会跳过树木而保留水和箱子，并验证六十四次读取的边界；最低高度在这里代表没有找到有效地面。
 */
class ClientSurfaceHeightTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.BLOCK.bindTags(Map.of(
                BlockTags.LOGS, List.of(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.BIRCH_LOG)),
                BlockTags.LEAVES, List.of(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.BIRCH_LEAVES))));
    }

    @Test
    void treesAreSkippedButWaterAndContainersStayVisible() {
        Column world = new Column();
        world.blocks.put(63, Blocks.GRASS_BLOCK.defaultBlockState());
        for (int y = 64; y < 69; y++) world.blocks.put(y, Blocks.BIRCH_LOG.defaultBlockState());
        world.blocks.put(69, Blocks.BIRCH_LEAVES.defaultBlockState());
        assertEquals(64, ClientSurfaceHeight.constructionGround(world, 0, 0, 70),
                "a birch tree must expose the ground for a clearing job");
        world.blocks.put(68, Blocks.WATER.defaultBlockState());
        assertEquals(69, ClientSurfaceHeight.constructionGround(world, 0, 0, 70),
                "water must remain visible to the site's fluid rejection");
        world.blocks.put(68, Blocks.CHEST.defaultBlockState());
        assertEquals(69, ClientSurfaceHeight.constructionGround(world, 0, 0, 70),
                "construction and containers must not be skipped as vegetation");
    }

    @Test
    void unboundedColumnIsRejectedWithoutScanningWorldHeight() {
        Column world = new Column();
        assertEquals(-64, ClientSurfaceHeight.constructionGround(world, 0, 0, 200),
                "a column with no bounded ground must be rejected");
        assertEquals(64, world.reads, "a floating canopy must not scan the world height");
    }

    /** 单列世界替身：按 y 记录方块，并数下被读取的次数。 */
    private static final class Column implements BlockGetter {
        final Map<Integer, BlockState> blocks = new HashMap<>();
        int reads;

        @Override public BlockState getBlockState(BlockPos pos) {
            reads++;
            return blocks.getOrDefault(pos.getY(), Blocks.AIR.defaultBlockState());
        }

        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override public int getHeight() {
            return 384;
        }

        @Override public int getMinBuildHeight() {
            return -64;
        }
    }
}
