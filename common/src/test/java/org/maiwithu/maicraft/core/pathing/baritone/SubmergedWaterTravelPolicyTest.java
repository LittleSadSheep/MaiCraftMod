package org.maiwithu.maicraft.core.pathing.baritone;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.maiwithu.maicraft.core.pathing.util.SwimAirBudget;

/**
 * 检查潜水姿势、缺氧上浮、补满气后再走、换段保留换气和水面两层空间。测试没有覆盖更深处的含水障碍。
 */
public final class SubmergedWaterTravelPolicyTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Bootstrap creates registry values, but a headless test never reloads vanilla data
        // packs. Bind the same fluid tags a real level provides before exercising fluid queries.
        BuiltInRegistries.FLUID.bindTags(Map.of(
                FluidTags.WATER, List.of(BuiltInRegistries.FLUID.wrapAsHolder(Fluids.WATER),
                        BuiltInRegistries.FLUID.wrapAsHolder(Fluids.FLOWING_WATER)),
                FluidTags.LAVA, List.of(BuiltInRegistries.FLUID.wrapAsHolder(Fluids.LAVA),
                        BuiltInRegistries.FLUID.wrapAsHolder(Fluids.FLOWING_LAVA))));
        diveWaitsForSwimmingPose();
        reserveSurfacesUntilAirRefills();
        airborneDryTicksCannotEndRefill();
        segmentsShareRecoveryButBodiesDoNot();
        shorelineReleasesTheSwimPhase();
        surfaceGeometryMatchesBothRouteConventions();
        System.out.println("SubmergedWaterTravelPolicyTest: passed");
    }

    private static void diveWaitsForSwimmingPose() {
        SwimTravelControl control = new SwimTravelControl();
        update(control, false, false, 63.3, 1.62, true, false, 300);
        check(control.phase() == SwimTravelControl.Phase.DIVING && control.verticalIntent() < 0,
                "surface walking must actively descend to initiate swimming");
        update(control, false, true, 62.3, 1.62, true, false, 298);
        check(control.phase() == SwimTravelControl.Phase.DIVING && control.sprinting(),
                "submerged upright eyes alone must not end the dive before swimming pose begins");
        update(control, true, true, 63.0, 0.4, true, false, 280);
        check(control.phase() == SwimTravelControl.Phase.CRUISING && control.verticalIntent() == 0
                        && control.sprinting(),
                "the real swimming eye height must produce a stable underwater cruise layer");
        update(control, true, true, 63.0, 0.4, true, false, 230);
        check(control.phase() == SwimTravelControl.Phase.CRUISING,
                "three seconds submerged must not interrupt a swim with ample ascent air");
        control.update(true, false, true, true, 50.1, 0.4, 64, 50, true, false,
                300, 300, SwimAirBudget.requiredAirForAscent(13.5, 1));
        check(control.verticalIntent() == 0,
                "an underwater route must retain its intended depth instead of rising past its goal");
    }

    private static void reserveSurfacesUntilAirRefills() {
        SwimTravelControl control = new SwimTravelControl();
        update(control, true, true, 63.0, 0.4, true, false, 100);
        int reserve = SwimAirBudget.requiredAirForAscent(0.6, 1.0);
        update(control, true, true, 63.0, 0.4, true, false, reserve);
        check(control.phase() == SwimTravelControl.Phase.SURFACING && control.verticalIntent() > 0,
                "the ascent reserve must trigger a deliberate rise");
        update(control, false, true, 62.0, 1.62, true, false, reserve - 1);
        check(control.phase() == SwimTravelControl.Phase.SURFACING && control.verticalIntent() > 0,
                "low air on the next tick must not turn surfacing OFF");
        update(control, false, false, 63.0, 1.62, true, false, 60);
        check(control.phase() == SwimTravelControl.Phase.REFILL && !control.sprinting()
                        && control.verticalIntent() > 0,
                "keep the head above water while the authoritative air supply recovers");
        update(control, false, false, 63.0, 1.62, true, false, 200);
        check(control.phase() == SwimTravelControl.Phase.REFILL, "refill ended too early");
        update(control, false, false, 63.0, 1.62, true, false, 300);
        check(control.phase() == SwimTravelControl.Phase.DIVING && control.verticalIntent() < 0,
                "a fully recovered body should resume the selected water route");
    }

    private static void shorelineReleasesTheSwimPhase() {
        SwimTravelControl control = new SwimTravelControl();
        update(control, true, true, 63.0, 0.4, true, false, 280);
        update(control, true, true, 63.0, 0.4, true, true, 279);
        check(control.phase() == SwimTravelControl.Phase.SURFACING,
                "begin rising before the route leaves deep water");
        control.update(false, true, false, false, 64, 1.62, 64, 63, false, true,
                280, 300, SwimAirBudget.requiredAirForAscent(0, 1));
        check(!control.active(), "walking onto land must release swim controls immediately");
    }

    private static void airborneDryTicksCannotEndRefill() {
        SwimTravelControl control = new SwimTravelControl();
        update(control, true, true, 63, 0.4, true, false, 150);
        update(control, true, true, 63, 0.4, true, false, 60);
        control.update(false, false, false, false, 64.05, 1.62, 64, 63,
                true, false, 78, 300, SwimAirBudget.requiredAirForAscent(0, 1));
        check(control.phase() == SwimTravelControl.Phase.REFILL && !control.sprinting(),
                "an airborne dry tick above the water must retain the refill episode");
        update(control, false, false, 63.7, 1.62, true, false, 105);
        check(control.phase() == SwimTravelControl.Phase.REFILL && control.verticalIntent() > 0,
                "returning to the water at air 105 must not restart a dive");
        update(control, false, true, 62.3, 1.62, true, false, 104);
        check(control.phase() == SwimTravelControl.Phase.SURFACING && !control.sprinting(),
                "a wave covering the eyes during refill must request another rise");
        control.update(false, false, false, false, 64.05, 1.62, 64, 63,
                false, true, 120, 300, SwimAirBudget.requiredAirForAscent(0, 1));
        check(control.phase() == SwimTravelControl.Phase.REFILL,
                "a temporary missing deep-water route must not stand in for a physical landing");
        control.releaseRoute(false, false, 130, 300);
        check(control.phase() == SwimTravelControl.Phase.REFILL,
                "a structural or replacement segment must retain unfinished refill");
        control.update(false, true, false, false, 64, 1.62, 64, 63,
                false, true, 150, 300, SwimAirBudget.requiredAirForAscent(0, 1));
        check(!control.active(), "a real dry grounded landing must release refill");
    }

    private static void segmentsShareRecoveryButBodiesDoNot() {
        SwimTravelControl.BodyState bodyState = new SwimTravelControl.BodyState();
        Object body = new Object();
        Object world = new Object();
        SwimTravelControl currentSegment = bodyState.bind(body, world);
        currentSegment.airBudget.observe(1, 200, true);
        currentSegment.airBudget.observe(2, 198, true);
        update(currentSegment, true, true, 63, 0.4, true, false, 150);
        SwimTravelControl plannedAhead = bodyState.bind(body, world);
        check(plannedAhead == currentSegment, "planning ahead must not copy a stale swim phase");
        check(plannedAhead.airBudget.airPerTick() == 2,
                "segment replacement forgot the body's observed oxygen consumption");
        update(currentSegment, true, true, 63, 0.4, true, false, 60);
        update(currentSegment, false, false, 63.7, 1.62, true, false, 78);
        check(plannedAhead.phase() == SwimTravelControl.Phase.REFILL,
                "the next segment missed air recovery that began after its construction");
        SwimTravelControl replanned = bodyState.bind(body, world);
        update(replanned, false, false, 63.7, 1.62, true, false, 105);
        check(replanned.phase() == SwimTravelControl.Phase.REFILL,
                "a replacement first segment must not start a new dive before full refill");
        check(!bodyState.bind(new Object(), world).active(), "respawn inherited another body's recovery");
        check(!bodyState.bind(body, new Object()).active(), "world replacement inherited stale recovery");
        bodyState.clear();
        check(!bodyState.bind(body, world).active(), "world teardown retained swim recovery");
    }

    private static void surfaceGeometryMatchesBothRouteConventions() {
        TestWater world = new TestWater();
        BlockPos top = new BlockPos(0, 63, 0);
        world.blocks.put(top, Blocks.WATER.defaultBlockState());
        world.blocks.put(top.below(), Blocks.WATER.defaultBlockState());
        check(top.equals(SubmergedWaterTravelPolicy.findWaterSurface(world, top))
                        && top.equals(SubmergedWaterTravelPolicy.findWaterSurface(world, top.above()))
                        && top.equals(SubmergedWaterTravelPolicy.findWaterSurface(world, top.below())),
                "water, air-above-water and already-submerged route nodes must share the actual surface");
        check(SubmergedWaterTravelPolicy.safeSurfaceColumn(world, top),
                "two clear water cells with an open surface must permit swimming");
        world.blocks.remove(top.below());
        check(!SubmergedWaterTravelPolicy.safeSurfaceColumn(world, top),
                "one-cell shallow water cannot fit the upright dive transition");
        world.blocks.put(top.below(), Blocks.OAK_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true));
        check(!SubmergedWaterTravelPolicy.safeSurfaceColumn(world, top),
                "waterlogged collision is not a clear dive layer");
        world.blocks.put(top.below(), Blocks.WATER.defaultBlockState());
        world.blocks.put(top.above(), Blocks.ICE.defaultBlockState());
        check(!SubmergedWaterTravelPolicy.safeSurfaceColumn(world, top),
                "a sealed surface must return breathing control to the rescue reflex");
    }

    private static void update(SwimTravelControl control, boolean swimming, boolean eyesWet,
                               double feetY, double eyeHeight, boolean deep, boolean shore, int air) {
        int reserve = SwimAirBudget.requiredAirForAscent(Math.max(0, 64 - feetY - eyeHeight), 1);
        control.update(true, false, swimming, eyesWet, feetY, eyeHeight, 64, 63,
                deep, shore, air, 300, reserve);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestWater implements BlockGetter {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }
}
