// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 落点能用哪些办法、放好后落不落得下：带着水桶就在落点倒水、带着干草就垫在落点抬高一格，
 * 现成的水源直接用；不许放东西、下界放水都不列；头顶太低放不下抬高后的身体就不收干草；
 * 放水的那一刻要留得出来，掉得太快就不收。
 */
class LandingOptionsTest {

    private static final BlockPos FEET = new BlockPos(0, 64, 0);

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 替身世界：没摆的格子都是空气。 */
    private static final class World implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        World floor() {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) blocks.put(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState());
            }
            return this;
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }

    private static LandingOptions carrying(LandingAid... aids) {
        return new LandingOptions(aids.length == 0 ? Set.of() : Set.of(aids), true, false, 0.6, 1.8);
    }

    private static boolean safe(World world, LandingPlan plan) {
        return LandingGeometry.safe(world, pos -> true, plan, 0.6, 1.8, new LongOpenHashSet());
    }

    @Test
    void carriedWaterIsPouredOnTheFloorAndCarriedHayRaisesTheFeet() {
        World world = new World().floor();
        List<LandingPlan> plans = carrying(LandingAid.WATER, LandingAid.HAY).plans(world, FEET, pos -> false);
        assertEquals(List.of(LandingAid.WATER, LandingAid.HAY), plans.stream().map(LandingPlan::aid).toList());
        LandingPlan water = plans.getFirst();
        assertEquals(FEET, water.cell());
        assertEquals(FEET.below(), water.clicked());
        assertEquals(Direction.UP, water.face());
        assertFalse(water.existing());
        assertTrue(safe(world, water));
        assertTrue(safe(world, plans.get(1)));
    }

    @Test
    void aLowCeilingRulesOutHayButNotWater() {
        World world = new World().floor();
        // 头顶第二格是石头：垫干草后身体抬高一格就顶住了，落进水里还放得下。
        world.blocks.put(FEET.above(2), Blocks.STONE.defaultBlockState());
        List<LandingPlan> plans = carrying(LandingAid.WATER, LandingAid.HAY).plans(world, FEET, pos -> false);
        assertTrue(safe(world, plans.getFirst()));
        assertFalse(safe(world, plans.get(1)));
    }

    @Test
    void existingSourceWaterNeedsNoBucketAndProtectedOrForbiddenCellsGetNothing() {
        World world = new World().floor();
        world.blocks.put(FEET, Blocks.WATER.defaultBlockState());
        List<LandingPlan> plans = carrying().plans(world, FEET, pos -> false);
        assertEquals(1, plans.size());
        assertTrue(plans.getFirst().existing());
        // 流动的水不是水源，接不住。
        world.blocks.put(FEET, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3));
        assertTrue(carrying().plans(world, FEET, pos -> false).isEmpty());
        world.blocks.remove(FEET);
        assertTrue(carrying(LandingAid.WATER).plans(world, FEET, pos -> pos.equals(FEET)).isEmpty());
        var forbidden = new LandingOptions(Set.of(LandingAid.WATER, LandingAid.HAY), false, false, 0.6, 1.8);
        assertTrue(forbidden.plans(world, FEET, pos -> false).isEmpty());
        var nether = new LandingOptions(Set.of(LandingAid.WATER), true, true, 0.6, 1.8);
        assertTrue(nether.plans(world, FEET, pos -> false).isEmpty());
    }

    @Test
    void grownBerryBushesAndFlowingWaterDoNotCushion() {
        BlockState young = Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 0);
        assertTrue(LandingAid.SWEET_BERRIES.cushions(young));
        assertFalse(LandingAid.SWEET_BERRIES.cushions(young.setValue(SweetBerryBushBlock.AGE, 2)));
        assertTrue(LandingAid.WATER.cushions(Blocks.WATER.defaultBlockState()));
        assertTrue(LandingAid.SLIME.cushions(Blocks.SLIME_BLOCK.defaultBlockState()));
    }

    @Test
    void onlyAnUnchangedOwnUnprotectedPlacementIsRecovered() {
        BlockState hay = Blocks.HAY_BLOCK.defaultBlockState();
        assertTrue(LandingPlan.canRecover(true, hay, hay, false));
        assertFalse(LandingPlan.canRecover(false, hay, hay, false));
        assertFalse(LandingPlan.canRecover(true, hay, Blocks.SLIME_BLOCK.defaultBlockState(), false));
        assertFalse(LandingPlan.canRecover(true, hay, hay, true));
    }

    @Test
    void theWaterMomentMustExistForTheWholeDrop() {
        var window = new WaterLandingWindow(0.08, 4.5, 1.62, 0);
        assertTrue(window.permits(30));
        // 掉一百多格后每刻下落比够得着的那一段还长：倒水的那一刻可能直接错过。
        assertFalse(window.permits(200));
        assertFalse(window.permits(0));
    }
}
