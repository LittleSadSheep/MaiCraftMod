// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementTraverse;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.pathing.util.SwimAirBudget;

/** Follows the selected water route in swimming pose, surfacing for air or the next shore. */
public final class SubmergedWaterTravelPolicy {
    private final IPlayerContext context;
    private final IPath path;
    private final SwimTravelControl control;
    private final SwimAirBudget airBudget;
    private int routeY;
    private double waterSurface;
    private IMovement controlledMovement;
    private boolean breathingEscapeKnown;

    public SubmergedWaterTravelPolicy(IPlayerContext context, IPath path, SwimTravelControl control) {
        this.context = context;
        this.path = path;
        this.control = control;
        this.airBudget = control.airBudget;
    }

    /** Update before ordinary path membership checks, which otherwise reject the dive's Y offset. */
    public void update(int pathPosition) {
        LocalPlayer player = context.player();
        controlledMovement = null;
        breathingEscapeKnown = false;
        if (player == null) return;
        boolean eyesWet = player.isEyeInFluid(FluidTags.WATER);
        airBudget.observe(player.level().getGameTime(), player.getAirSupply(), eyesWet);
        BlockPos bodySurface = findWaterSurface(BlockPos.containing(player.getEyePosition()));
        breathingEscapeKnown = bodySurface != null && safeSurfaceColumn(bodySurface);
        IMovement current = pathPosition >= 0 && pathPosition < path.movements().size()
                ? path.movements().get(pathPosition) : null;
        boolean flatWaterMove = flatMovement(current);
        if (!flatWaterMove) {
            control.releaseRoute(player.isInWater(), player.onGround(),
                    player.getAirSupply(), player.getMaxAirSupply());
            return;
        }
        routeY = current.getSrc().getY();
        BlockPos surface = flatWaterMove ? findWaterSurface(current.getSrc()) : null;
        boolean deepRoute = surface != null && safeDeepMovement(current, surface.getY());
        if (deepRoute) {
            waterSurface = surface.getY()
                    + context.world().getFluidState(surface).getHeight(context.world(), surface);
        }
        double rise = Math.max(0, waterSurface - player.getEyeY());
        int reserve = SwimAirBudget.requiredAirForAscent(rise, airBudget.airPerTick());
        // Only the distance physically needed to rise before land matters. No arbitrary minimum
        // river length is required, and lookahead stops once enough water has been proved.
        double speed = Math.max(0.15, player.getDeltaMovement().horizontalDistance());
        double runway = rise / 0.12 * speed + player.getBbWidth();
        boolean approachingShore = deepRoute && routeY >= surface.getY()
                && safeDeepRunDistance(pathPosition, surface.getY(), runway + 1.0) <= runway;
        control.update(player.isInWater(), player.onGround(), player.isSwimming(), eyesWet, player.getY(),
                player.getEyeHeight(), waterSurface, routeY, deepRoute, approachingShore,
                player.getAirSupply(), player.getMaxAirSupply(), reserve);
        if (control.active() && flatWaterMove) controlledMovement = current;
    }

    public boolean controls(int pathPosition, IMovement movement) {
        return control.active() && controlledMovement == movement;
    }

    /** Permit the intentional depth offset while the same horizontal route corridor is owned. */
    public BetterBlockPos routeFeet(BetterBlockPos physicalFeet) {
        return controlledMovement == null || !control.active() ? physicalFeet
                : new BetterBlockPos(physicalFeet.getX(), routeY, physicalFeet.getZ());
    }

    public boolean movementReached(int pathPosition, IMovement movement) {
        if (!controls(pathPosition, movement) || !atDestination(movement)) return false;
        return !control.recovering()
                || (!context.player().isEyeInFluid(FluidTags.WATER)
                        && context.player().getAirSupply() >= context.player().getMaxAirSupply());
    }

    /** At a shoreline endpoint, rise instead of swimming past the selected movement. */
    public boolean movingForward() {
        return controlledMovement != null
                && !(control.recovering() && atDestination(controlledMovement));
    }

    private boolean atDestination(IMovement movement) {
        return Mth.floor(context.player().getX()) == movement.getDest().getX()
                && Mth.floor(context.player().getZ()) == movement.getDest().getZ();
    }

    public boolean active() { return control.recovering() || (control.active() && controlledMovement != null); }
    public boolean managesAir() { return active() && breathingEscapeKnown; }
    public boolean sprinting() { return control.sprinting(); }
    public int verticalIntent() { return control.verticalIntent(); }
    public float cameraPitch() { return control.cameraPitch(); }

    private double safeDeepRunDistance(int start, int surfaceY, double enoughDistance) {
        double distance = 0;
        for (int index = start; index < path.movements().size(); index++) {
            IMovement movement = path.movements().get(index);
            if (!safeDeepMovement(movement, surfaceY)) break;
            distance += index == start
                    ? Math.hypot(context.player().getX() - movement.getDest().getX() - 0.5,
                            context.player().getZ() - movement.getDest().getZ() - 0.5)
                    : Math.hypot(movement.getDirection().getX(), movement.getDirection().getZ());
            if (distance > enoughDistance) break;
        }
        return distance;
    }

    private static boolean flatMovement(IMovement movement) {
        return (movement instanceof MovementTraverse || movement instanceof MovementDiagonal)
                && movement.getSrc().getY() == movement.getDest().getY();
    }

    private boolean safeDeepMovement(IMovement movement, int surfaceY) {
        if (!flatMovement(movement)) return false;
        BlockPos src = movement.getSrc();
        BlockPos dest = movement.getDest();
        if (!safeSurfaceColumn(new BlockPos(src.getX(), surfaceY, src.getZ()))
                || !safeSurfaceColumn(new BlockPos(dest.getX(), surfaceY, dest.getZ()))) return false;
        if (movement instanceof MovementDiagonal) {
            return safeSurfaceColumn(new BlockPos(src.getX(), surfaceY, dest.getZ()))
                    && safeSurfaceColumn(new BlockPos(dest.getX(), surfaceY, src.getZ()));
        }
        return true;
    }

    /** Accept both a water node and the air node above it, then locate its actual surface. */
    private BlockPos findWaterSurface(BlockPos route) {
        if (!context.world().hasChunkAt(route)) return null;
        return findWaterSurface(context.world(), route);
    }

    static BlockPos findWaterSurface(BlockGetter world, BlockPos route) {
        BlockPos cursor = route;
        if (!world.getFluidState(cursor).is(FluidTags.WATER)) cursor = cursor.below();
        if (!world.getFluidState(cursor).is(FluidTags.WATER)) return null;
        while (cursor.getY() + 1 < world.getMaxBuildHeight()
                && world.getFluidState(cursor.above()).is(FluidTags.WATER)) cursor = cursor.above();
        return cursor;
    }

    /** Two clear water cells and a breathable surface provide the body and its exit corridor. */
    private boolean safeSurfaceColumn(BlockPos surface) {
        if (!context.world().hasChunkAt(surface)) return false;
        return safeSurfaceColumn(context.world(), surface)
                && !EmbeddedBaritonePolicy.forbidsBody(surface)
                && !EmbeddedBaritonePolicy.forbidsBody(surface.below());
    }

    static boolean safeSurfaceColumn(BlockGetter world, BlockPos surface) {
        BlockPos lower = surface.below();
        BlockPos above = surface.above();
        BlockState upperState = world.getBlockState(surface);
        BlockState lowerState = world.getBlockState(lower);
        BlockState aboveState = world.getBlockState(above);
        BlockState floor = world.getBlockState(lower.below());
        return clearWater(world, surface, upperState) && clearWater(world, lower, lowerState)
                && aboveState.getFluidState().isEmpty()
                && aboveState.getCollisionShape(world, above).isEmpty()
                && !floor.is(Blocks.MAGMA_BLOCK) && !floor.is(Blocks.SOUL_SAND)
                && !floor.getFluidState().is(FluidTags.LAVA);
    }

    private static boolean clearWater(BlockGetter world, BlockPos position, BlockState state) {
        return state.getFluidState().is(FluidTags.WATER) && !state.is(Blocks.BUBBLE_COLUMN)
                && state.getCollisionShape(world, position).isEmpty();
    }
}
