// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.Baritone;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;

/** Continuous-ground acceleration; upstream Parkour and sprint-ascend still own their special moves. */
public final class TravelJumpPolicy {
    private static final float NORMAL_GROUND_FRICTION = 0.6F;
    private TravelJumpPolicy() {}

    public static boolean shouldTravelJump(Baritone baritone, List<IMovement> movements,
                                           int pathPosition, java.util.function.Consumer<List<IMovement>> verifiedRunway) {
        IPlayerContext ctx = baritone.getPlayerContext();
        var player = ctx.player();
        if (player == null || !player.onGround() || player.isInWater() || player.isPassenger()
                || player.isCrouching() || player.hasEffect(MobEffects.LEVITATION)
                || player.hasEffect(MobEffects.SLOW_FALLING)) return false;
        var input = baritone.getInputOverrideHandler();
        if (input.isInputForcedDown(Input.CLICK_LEFT) || input.isInputForcedDown(Input.CLICK_RIGHT)
                || input.isInputForcedDown(Input.SNEAK)) return false;
        TravelRunway runway = TravelRunway.capture(movements, pathPosition, player.position());
        if (runway == null || player.getDeltaMovement().dot(runway.heading()) <= 0
                || !isStableTakeoff(ctx, ctx.playerFeet())) return false;
        var policy = EmbeddedBaritonePolicy.snapshot();
        if (player.getY() < policy.minimumFeetY()) return false;
        Plan plan = plan(ctx.world(), ctx.world()::isLoaded, policy.forbiddenBodyCells(),
                EmbeddedBaritoneRuntime.physicalObstacles(), runway,
                TravelJumpPhysics.capture(player, runway.heading()), player.getBbWidth(), player.getDeltaMovement());
        if (plan == null || !FallDamageBudget.capture(player).survives(plan.apexHeight(),
                FallDamageBudget.Landing.ORDINARY, false)) return false;
        verifiedRunway.accept(plan.movements());
        return true;
    }

    record Plan(List<IMovement> movements, double apexHeight, boolean headHit) {}

    /** Uses the same swept body/floor geometry for grid steps and smoothed arbitrary bearings. */
    static Plan plan(BlockGetter world, Predicate<BlockPos> loaded, LongSet forbidden,
                     PhysicalObstacleSnapshot physical, TravelRunway runway,
                     TravelJumpPhysics.Launch launch, double width, Vec3 velocity) {
        if (runway == null || !Double.isFinite(velocity.lengthSqr() + width) || width <= 0) return null;
        double sideSpeed = Math.abs(-runway.heading().z * velocity.x + runway.heading().x * velocity.z);
        for (boolean headHit : new boolean[]{true, false}) {
            var flight = TravelJumpPhysics.project(launch, headHit);
            if (flight == null) continue;
            double reach = flight.forwardDistance() + width * .5 + .25;
            List<IMovement> verified = runway.covering(reach);
            if (verified.isEmpty()) continue;
            double drift = sideSpeed * flight.airDragSum();
            var corridor = new GroundCorridor(world, loaded, width + 2 * drift,
                    launch.bodyHeight() + flight.apexHeight(), forbidden, physical);
            Vec3 end = runway.point(reach);
            // A gap in the canopy must reserve a full flight, not assume an early head collision.
            if (headHit && !corridor.hasContinuousCeiling(runway.start(), end, 2)) continue;
            if (corridor.clear(runway.start(), end)) return new Plan(verified, flight.apexHeight(), headHit);
        }
        return null;
    }

    /** Full dry support keeps takeoff friction and height consistent with the projected flight. */
    private static boolean isStableTakeoff(IPlayerContext ctx, BlockPos feet) {
        BlockState support = ctx.world().getBlockState(feet.below());
        return support.getFluidState().isEmpty()
                && support.isCollisionShapeFullBlock(ctx.world(), feet.below())
                && support.getBlock().getFriction() <= NORMAL_GROUND_FRICTION
                && MovementHelper.fullyPassable(ctx, feet)
                && MovementHelper.fullyPassable(ctx, feet.above());
    }
}
