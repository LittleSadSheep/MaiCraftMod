// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 哪一格水能扔料：认的流体的源格、水不往外流、头顶空着；排先后时漂着别人东西的往后、再按远近。 */
class TransformSitesTest {

    private static final Set<String> WATER = Set.of("minecraft:water", "minecraft:flowing_water");
    private static final BlockPos POOL = new BlockPos(0, 64, 0);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 一格水坑：四面和底下是石头，头顶是空气。 */
    private static Pond pond() {
        Pond pond = new Pond();
        pond.blocks.put(POOL, Blocks.WATER.defaultBlockState());
        pond.blocks.put(POOL.below(), Blocks.STONE.defaultBlockState());
        for (Direction side : Direction.Plane.HORIZONTAL) pond.blocks.put(POOL.relative(side), Blocks.STONE.defaultBlockState());
        return pond;
    }

    @Test
    void 石头围着的一格水源_头顶空着_能扔料() {
        assertTrue(TransformSites.usable(pond(), POOL, WATER));
    }

    @Test
    void 头顶盖着_流动的水_别的流体_都不能扔() {
        Pond covered = pond();
        covered.blocks.put(POOL.above(), Blocks.STONE.defaultBlockState());
        assertFalse(TransformSites.usable(covered, POOL, WATER), "头顶盖着扔不进去");

        Pond flowing = pond();
        flowing.blocks.put(POOL, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 2));
        assertFalse(TransformSites.usable(flowing, POOL, WATER), "流动的水不是源格");

        Pond lava = pond();
        lava.blocks.put(POOL, Blocks.LAVA.defaultBlockState());
        assertFalse(TransformSites.usable(lava, POOL, WATER), "配方认的是水");
    }

    @Test
    void 水往旁边流的源格不扔_东西会漂走() {
        Pond leaking = pond();
        // 东边一格是更低的流水：这格水源往东流，扔进去的料会被冲开。
        leaking.blocks.put(POOL.east(), Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3));
        leaking.blocks.put(POOL.east().below(), Blocks.STONE.defaultBlockState());
        assertFalse(TransformSites.usable(leaking, POOL, WATER));
    }

    @Test
    void 排先后_没漂着东西的在前_再按远近() {
        BlockPos near = new BlockPos(1, 64, 0);
        BlockPos far = new BlockPos(9, 64, 0);
        BlockPos crowdedNear = new BlockPos(0, 64, 1);
        List<BlockPos> order = TransformSites.order(List.of(
                new TransformSites.Pool(crowdedNear, 1.0, true),
                new TransformSites.Pool(far, 9.0, false),
                new TransformSites.Pool(near, 1.0, false)));
        assertEquals(List.of(near, far, crowdedNear), order);
    }

    /** 摆了几格方块的世界替身，其余是空气。 */
    private static final class Pond implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        @Override public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override public int getHeight() {
            return 384;
        }

        @Override public int getMinBuildHeight() {
            return -64;
        }
    }
}
