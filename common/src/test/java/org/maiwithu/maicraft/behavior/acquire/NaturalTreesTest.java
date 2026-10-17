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

/** 天然树的辨认：顶上有树冠的原木是树，光秃秃的原木柱、玩家放的持久树叶都不是。 */
class NaturalTreesTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 替身世界：没摆的格子都是空气。 */
    private static final class World implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void log(int x, int y, int z) {
            blocks.put(new BlockPos(x, y, z), Blocks.OAK_LOG.defaultBlockState());
        }

        void leaf(int x, int y, int z, int distance, boolean persistent) {
            blocks.put(new BlockPos(x, y, z), Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(LeavesBlock.DISTANCE, distance).setValue(LeavesBlock.PERSISTENT, persistent));
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }

    private static World tree(boolean persistentLeaves) {
        World world = new World();
        for (int y = 64; y <= 67; y++) world.log(0, y, 0);
        // 树冠：树顶两层四周贴着的叶子距离 1，再往外一圈距离 2，一共十几片。
        for (int y = 66; y <= 67; y++) {
            world.leaf(1, y, 0, 1, persistentLeaves);
            world.leaf(-1, y, 0, 1, persistentLeaves);
            world.leaf(0, y, 1, 1, persistentLeaves);
            world.leaf(0, y, -1, 1, persistentLeaves);
            world.leaf(2, y, 0, 2, persistentLeaves);
            world.leaf(-2, y, 0, 2, persistentLeaves);
        }
        return world;
    }

    @Test
    void 顶上有树冠的原木是树_从树干哪一格看都一样() {
        World world = tree(false);
        assertTrue(NaturalTrees.partOfNaturalTree(new BlockPos(0, 64, 0), world, pos -> true, new HashMap<>()));
        assertTrue(NaturalTrees.partOfNaturalTree(new BlockPos(0, 67, 0), world, pos -> true, new HashMap<>()));
    }

    @Test
    void 光秃秃的原木柱不是树_木屋的横梁也不是() {
        World world = new World();
        for (int y = 64; y <= 66; y++) world.log(5, y, 0);
        assertFalse(NaturalTrees.partOfNaturalTree(new BlockPos(5, 65, 0), world, pos -> true, new HashMap<>()));
    }

    @Test
    void 玩家放的持久树叶不算树冠() {
        World world = tree(true);
        assertFalse(NaturalTrees.partOfNaturalTree(new BlockPos(0, 64, 0), world, pos -> true, new HashMap<>()));
    }
}
