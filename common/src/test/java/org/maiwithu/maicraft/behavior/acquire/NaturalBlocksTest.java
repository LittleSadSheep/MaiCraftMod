// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 缺材料时只挖地图上自然长的：石头、矿、树上的原木算；圆石墙、木板、刷石机里的圆石这些搭出来的不算。 */
class NaturalBlocksTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 替身世界：没摆的格子都是空气。 */
    private static final class World implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void put(int x, int y, int z, BlockState state) {
            blocks.put(new BlockPos(x, y, z), state);
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }

    private static boolean grown(World world, BlockState state, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        world.put(x, y, z, state);
        return NaturalBlocks.grown(state, pos, world, ignored -> true, new HashMap<>());
    }

    @Test
    void 天然地形和矿能挖() {
        World world = new World();
        assertTrue(grown(world, Blocks.STONE.defaultBlockState(), 0, 64, 0));
        assertTrue(grown(world, Blocks.IRON_ORE.defaultBlockState(), 1, 64, 0));
        assertTrue(grown(world, Blocks.DIRT.defaultBlockState(), 2, 64, 0));
    }

    @Test
    void 搭出来的不挖() {
        World world = new World();
        assertFalse(grown(world, Blocks.COBBLESTONE.defaultBlockState(), 0, 64, 0), "刷石机、墙里的圆石");
        assertFalse(grown(world, Blocks.OAK_PLANKS.defaultBlockState(), 1, 64, 0), "房子的木板");
        assertFalse(grown(world, Blocks.SMOOTH_STONE.defaultBlockState(), 2, 64, 0), "平台的平滑石");
        assertFalse(grown(world, Blocks.GLASS.defaultBlockState(), 3, 64, 0));
    }

    @Test
    void 名单外的原木长在树上也算() {
        // 模组的原木（这里拿去皮原木代替，名单里没有它）顶上有树冠就算树。
        World world = new World();
        BlockState log = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
        for (int y = 64; y <= 67; y++) world.put(0, y, 0, log);
        BlockState leaf = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1)
                .setValue(LeavesBlock.PERSISTENT, false);
        for (int y = 66; y <= 67; y++) {
            for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}}) {
                world.put(side[0], y, side[1], leaf);
            }
        }
        assertTrue(NaturalBlocks.grown(log, new BlockPos(0, 64, 0), world, ignored -> true, new HashMap<>()));
    }
}
