/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 only.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement.movements;

import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.MovementState.MovementTarget;
import baritone.utils.pathing.MutableMoveResult;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.phys.Vec3;

public class MovementFall extends Movement {
    private org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistSession landingAssist;
    private org.maiwithu.maicraft.core.pathing.baritone.landing.BoatLandingAssist landingBoat;
    private org.maiwithu.maicraft.core.pathing.baritone.landing.BoatLandingAssist.State boatState;
    private boolean departureObserved;

    public org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistSession landingAssist() { return landingAssist; }
    public org.maiwithu.maicraft.core.pathing.baritone.landing.BoatLandingAssist landingBoat() { return landingBoat; }
    public void tickLandingBoat(org.maiwithu.maicraft.client.actor.LocalPlayerContext context) {
        boatState = landingBoat.tick(context);
    }

    /** Shared by ordinary execution and the executor's optional straight-line fall extension. */
    public static boolean reachedLanding(IPlayerContext context, BlockPos destination, BlockState state) {
        return context.playerFeet().equals(destination)
                && (context.player().onGround() || MovementHelper.isWater(state));
    }

    public MovementFall(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest) {
        super(baritone, src, dest, MovementFall.buildPositionsToBreak(src, dest));
    }

    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult result = new MutableMoveResult();
        MovementDescend.cost(context, src.x, src.y, src.z, dest.x, dest.z, result);
        if (result.y != dest.y) {
            return COST_INF; // doesn't apply to us, this position is a descend not a fall
        }
        return result.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        set.add(src);
        for (int y = src.y - dest.y; y >= 0; y--) {
            set.add(dest.above(y));
        }
        return set;
    }

    private boolean needsLandingAssist() {
        CalculationContext context = new CalculationContext(baritone);
        MutableMoveResult result = new MutableMoveResult();
        return MovementDescend.dynamicFallCost(context, src.x, src.y, src.z, dest.x, dest.z, 0,
                context.get(dest.x, src.y - 2, dest.z), result) && result.y == dest.y;
    }

    @Override
    public MovementState updateState(MovementState state) {
        // Completion already includes supported ground, water or climbable stability evidence.
        // Requiring onGround again strands safely failed sessions that finished in another cell.
        if (landingAssist != null && landingAssist.complete()) {
            return state.setStatus(!landingAssist.failed() && ctx.playerFeet().equals(dest)
                    ? MovementStatus.SUCCESS : MovementStatus.UNREACHABLE);
        }
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        if (landingAssist == null && landingBoat == null && !ctx.player().onGround()
                && (org.maiwithu.maicraft.core.pathing.baritone.landing.EmergencyLanding.triggered(ctx.player())
                    || org.maiwithu.maicraft.core.pathing.baritone.FallDamageBudget.capture(ctx.player()).damage(
                        Math.max(0, ctx.player().getY() - dest.getY()),
                        org.maiwithu.maicraft.core.pathing.baritone.FallDamageBudget.Landing.of(ctx.world().getBlockState(dest.below())), true) > 0)) {
            var context = org.maiwithu.maicraft.client.runtime.ClientRuntime.requireContext(ctx.player());
            adoptEmergencyLanding(context);
        }
        if (landingBoat != null) {
            state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(
                    ctx.playerHead(), landingBoat.aimPoint(), ctx.playerRotations()), true));
            state.setInput(Input.SNEAK, landingBoat.wantsSneak());
            if (boatState == org.maiwithu.maicraft.core.pathing.baritone.landing.BoatLandingAssist.State.SETTLED)
                return state.setStatus(ctx.playerFeet().equals(dest) ? MovementStatus.SUCCESS : MovementStatus.UNREACHABLE);
            if (!landingBoat.failed()) return state;
            if (ctx.player().onGround()) return state.setStatus(MovementStatus.UNREACHABLE);
            // A failed opportunistic mount leaves the proven ordinary fall's steering active.
        }

        BlockPos playerFeet = ctx.playerFeet();
        Rotation toDest = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations());
        BlockState destState = ctx.world().getBlockState(dest);

        if (ctx.world().getBlockState(dest.below()).is(Blocks.MAGMA_BLOCK) && MovementHelper.steppingOnBlocks(ctx).stream().allMatch(block -> MovementHelper.canWalkThrough(ctx, block))) {
            state.setInput(Input.SNEAK, true);
        }

        boolean isWater = destState.getFluidState().getType() instanceof WaterFluid;
        state.setTarget(new MovementTarget(toDest, false));
        // Slab feet are represented by the cell above the half-height support. Matching that
        // cell is not a landing until collision has actually put the player on the ground.
        if (reachedLanding(ctx, dest, destState)) {
            if (landingAssist != null) {
                var context = org.maiwithu.maicraft.client.runtime.ClientRuntime.requireContext(ctx.player());
                state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(
                        ctx.playerHead(), landingAssist.aimPoint(), ctx.playerRotations()), true));
                state.setInput(Input.SNEAK, landingAssist.wantsSneak(context));
                if (!landingAssist.complete()) return state;
                return state.setStatus(landingAssist.failed() ? MovementStatus.UNREACHABLE : MovementStatus.SUCCESS);
            }
            if (!isWater || ctx.player().getDeltaMovement().y >= 0) return state.setStatus(MovementStatus.SUCCESS);
        }
        Vec3 destCenter = VecUtils.getBlockPosCenter(dest); // we are moving to the 0.5 center not the edge (like if we were falling on a ladder)
        if (Math.abs(ctx.player().position().x + ctx.player().getDeltaMovement().x - destCenter.x) > 0.1 || Math.abs(ctx.player().position().z + ctx.player().getDeltaMovement().z - destCenter.z) > 0.1) {
            if (!ctx.player().onGround() && Math.abs(ctx.player().getDeltaMovement().y) > 0.4) {
                state.setInput(Input.SNEAK, true);
            }
            state.setInput(Input.MOVE_FORWARD, true);
        }
        Vec3i avoid = Optional.ofNullable(avoid()).map(Direction::getNormal).orElse(null);
        if (avoid == null) {
            avoid = src.subtract(dest);
        } else {
            double dist = Math.abs(avoid.getX() * (destCenter.x - avoid.getX() / 2.0 - ctx.player().position().x)) + Math.abs(avoid.getZ() * (destCenter.z - avoid.getZ() / 2.0 - ctx.player().position().z));
            if (dist < 0.6) {
                state.setInput(Input.MOVE_FORWARD, true);
            } else if (!ctx.player().onGround()) {
                state.setInput(Input.SNEAK, false);
            }
        }
        Vec3 destCenterOffset = new Vec3(destCenter.x + 0.125 * avoid.getX(), destCenter.y, destCenter.z + 0.125 * avoid.getZ());
        state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), destCenterOffset, ctx.playerRotations()), false));
        if (landingAssist != null) {
            var context = org.maiwithu.maicraft.client.runtime.ClientRuntime.requireContext(ctx.player());
            state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(
                    ctx.playerHead(), landingAssist.aimPoint(), ctx.playerRotations()), true));
            state.setInput(Input.SNEAK, landingAssist.wantsSneak(context));
        }
        return state;
    }

    private void adoptEmergencyLanding(org.maiwithu.maicraft.client.actor.LocalPlayerContext context) {
        // The scheduler cannot safely suspend a launched fall. Adopt its rescue in this owner
        // instead of waiting for a reflex hand-off that cannot occur until after impact.
        if (landingAssist != null || landingBoat != null) return;
        var candidate = org.maiwithu.maicraft.core.pathing.baritone.landing.EmergencyLanding.find(context, dest);
        var inventory = org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPlan.InventorySnapshot.capture(
                context.player(), org.maiwithu.maicraft.core.pathing.moves.TerrainPermit.LANDING_ONLY,
                context.level().dimensionType().ultraWarm());
        boolean available = candidate != null && (candidate.plan().existing() || inventory.available().contains(candidate.plan().kind()));
        if (!available && org.maiwithu.maicraft.core.pathing.baritone.FallDamageBudget.capture(context.player()).survives(
                Math.max(0, context.player().getY() - dest.getY()),
                org.maiwithu.maicraft.core.pathing.baritone.FallDamageBudget.Landing.ORDINARY, true)) {
            var boat = org.maiwithu.maicraft.core.pathing.baritone.landing.BoatLandingAssist.airbornePlan(context, dest);
            if (boat != null) {
                landingBoat = new org.maiwithu.maicraft.core.pathing.baritone.landing.BoatLandingAssist(boat);
                return;
            }
        }
        landingAssist = candidate;
    }

    private Direction avoid() {
        for (int i = 0; i < 15; i++) {
            BlockState state = ctx.world().getBlockState(ctx.playerFeet().below(i));
            if (state.getBlock() == Blocks.LADDER) {
                return state.getValue(LadderBlock.FACING);
            }
        }
        return null;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // if we haven't started walking off the edge yet, or if we're in the process of breaking blocks before doing the fall
        // then it's safe to cancel this
        return !departureObserved && ctx.player().onGround() && ctx.playerFeet().equals(src)
                || state.getStatus() != MovementStatus.RUNNING;
    }

    private static BetterBlockPos[] buildPositionsToBreak(BetterBlockPos src, BetterBlockPos dest) {
        BetterBlockPos[] toBreak;
        int diffX = src.getX() - dest.getX();
        int diffZ = src.getZ() - dest.getZ();
        int diffY = Math.abs(src.getY() - dest.getY());
        toBreak = new BetterBlockPos[diffY + 2];
        for (int i = 0; i < toBreak.length; i++) {
            toBreak[i] = new BetterBlockPos(src.getX() - diffX, src.getY() + 1 - i, src.getZ() - diffZ);
        }
        return toBreak;
    }

    @Override
    protected boolean prepared(MovementState state) {
        boolean atDeparture = ctx.player().onGround() && ctx.playerFeet().equals(src);
        departureObserved |= !atDeparture;
        if (landingAssist == null && !departureObserved && atDeparture && needsLandingAssist()) {
            var calculation = new CalculationContext(baritone);
            var plans = calculation.landingPlans(dest, src.y - dest.y);
            var boatPlan = plans.stream().anyMatch(plan -> plan.existing() || calculation.landingInventory.available().contains(plan.kind()))
                    ? null : calculation.landingBoatPlan(src, dest);
            if (boatPlan != null) landingBoat = new org.maiwithu.maicraft.core.pathing.baritone.landing.BoatLandingAssist(boatPlan);
            else if (!plans.isEmpty()) landingAssist = org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistSession.automatic(plans, false);
        }
        if (landingBoat != null) {
            var context = org.maiwithu.maicraft.client.runtime.ClientRuntime.requireContext(ctx.player());
            // PREPPING is cancellable upstream. An in-air opportunity or a native mount
            // must remain RUNNING until the boat session observes a supported exit.
            if (!ctx.player().onGround()) return true;
            if (!landingBoat.prepare(context)) {
                if (landingBoat.failed() && ctx.player().onGround()) state.setStatus(MovementStatus.UNREACHABLE);
                return false;
            }
            return true;
        }
        if (landingAssist != null && !departureObserved && atDeparture) {
            var context = org.maiwithu.maicraft.client.runtime.ClientRuntime.requireContext(ctx.player());
            if (!landingAssist.prepare(context)) {
                if (landingAssist.failed() && !landingAssist.cleanupPending()) state.setStatus(MovementStatus.UNREACHABLE);
                return false;
            }
        }
        // Runs before every tick that could leave the source, including RUNNING. A prior fall,
        // incoming damage, an expired buff or removed boots must invalidate the stale A* budget.
        // After departure retain the selected landing through cleanup, including a neighboring
        // supported cell: the bucket consumed by this fall cannot invalidate its own recovery.
        if (!departureObserved && atDeparture
                && calculateCost(new CalculationContext(baritone)) >= COST_INF) {
            state.setStatus(MovementStatus.UNREACHABLE);
            return true;
        }
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        // only break if one of the first three needs to be broken
        // specifically ignore the last one which might be water
        for (int i = 0; i < 4 && i < positionsToBreak.length; i++) {
            if (landingAssist != null && positionsToBreak[i].equals(landingAssist.plan().cell())) continue;
            if (!MovementHelper.canWalkThrough(ctx, positionsToBreak[i])) {
                return super.prepared(state);
            }
        }
        return true;
    }
}
