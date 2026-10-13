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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 去水边装水只舀舀了会自己补回来的水：池塘里的一格算，刷石机里孤零零的水、头顶盖着的不算。 */
class WaterFillSourceTest {

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

    private static World pond() {
        World world = new World();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                world.put(x, 63, z, Blocks.DIRT.defaultBlockState());
                world.put(x, 64, z, Blocks.WATER.defaultBlockState());
            }
        }
        return world;
    }

    @Test
    void 池塘里的一格舀了会补回来() {
        assertTrue(WaterFillSource.refills(pond(), new BlockPos(1, 64, 1)));
        assertTrue(WaterFillSource.refills(pond(), new BlockPos(0, 64, 0)), "角上两边挨着水源也算");
    }

    @Test
    void 刷石机里孤零零的水不舀() {
        World world = new World();
        world.put(0, 63, 0, Blocks.COBBLESTONE.defaultBlockState());
        world.put(0, 64, 0, Blocks.WATER.defaultBlockState());
        world.put(1, 64, 0, Blocks.WATER.defaultBlockState());
        assertFalse(WaterFillSource.refills(world, new BlockPos(0, 64, 0)), "只挨着一格水源，舀了就没了");
    }

    @Test
    void 头顶盖着的不舀() {
        World world = pond();
        world.put(1, 65, 1, Blocks.OAK_PLANKS.defaultBlockState());
        assertFalse(WaterFillSource.refills(world, new BlockPos(1, 64, 1)));
    }
}
