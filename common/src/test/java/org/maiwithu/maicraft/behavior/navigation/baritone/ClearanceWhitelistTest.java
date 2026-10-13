// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

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
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 寻路能清掉的地形：长在树上的原木、自然长的树叶算；木屋的原木屋顶、地板，人放的树叶不算。 */
class ClearanceWhitelistTest {

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

    private static BlockState leaves(int distance, boolean persistent) {
        return Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, distance)
                .setValue(LeavesBlock.PERSISTENT, persistent);
    }

    private static boolean clearable(World world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return ClearanceWhitelist.allowsAt(world.getBlockState(pos), pos, world, ignored -> true, new HashMap<>());
    }

    @Test
    void 长在树上的原木能挖() {
        World world = new World();
        for (int y = 64; y <= 68; y++) world.put(0, y, 0, Blocks.SPRUCE_LOG.defaultBlockState());
        // 树冠：树顶两层四面贴着距离 1 的叶子，外面一圈距离 2。
        for (int y = 67; y <= 68; y++) {
            for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                world.put(side[0], y, side[1], leaves(1, false));
                world.put(side[0] * 2, y, side[1] * 2, leaves(2, false));
            }
        }
        assertTrue(clearable(world, 0, 64, 0), "树干底下那格照样算树");
        assertTrue(clearable(world, 1, 67, 0), "自然长的树叶能挖");
    }

    @Test
    void 村里木屋的原木屋顶和地板不挖() {
        // 云杉木屋：横着的原木搭成人字形屋顶，屋里竖着的原木当地板，旁边没有树叶。
        World world = new World();
        BlockState roof = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        for (int z = 95; z <= 101; z++) {
            world.put(533, 82, z, roof);
            world.put(534, 83, z, roof);
            world.put(535, 82, z, roof);
        }
        world.put(534, 77, 98, Blocks.SPRUCE_LOG.defaultBlockState());
        world.put(534, 76, 98, Blocks.DIRT.defaultBlockState());
        assertFalse(clearable(world, 534, 83, 98), "屋脊不挖");
        assertFalse(clearable(world, 533, 82, 98), "屋顶不挖");
        assertFalse(clearable(world, 534, 77, 98), "原木地板不挖");
        assertTrue(clearable(world, 534, 76, 98), "地板下面的泥土照样是地形");
    }

    @Test
    void 人放的树叶不挖() {
        World world = new World();
        world.put(0, 64, 0, leaves(7, true));
        assertFalse(clearable(world, 0, 64, 0));
    }
}
