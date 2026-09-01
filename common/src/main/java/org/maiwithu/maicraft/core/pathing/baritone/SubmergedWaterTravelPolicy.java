// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementTraverse;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Physical first-person swim phase for an already-selected deep-water route.
 *
 * <p>The path remains authoritative: this policy neither adds a node nor changes terrain. It may
 * only lower the real body below a contiguous sequence of loaded, two-cell-deep surface-water
 * movements which the path already owns. Before the route reaches shallow water, land or a
 * structural movement, it raises the body back to the surface and hands control to the ordinary
 * movement implementation.</p>
 */
public final class SubmergedWaterTravelPolicy {

    private enum Phase { OFF, DIVING, CRUISING, SURFACING }

    /** Enough selected water runway for the dive/rise transition to pay for itself. */
    private static final double MIN_EFFICIENT_RUN_BLOCKS = 8.0D;
    /** Begin rising while these final selected water blocks are still available. */
    private static final double SURFACE_RUNWAY_BLOCKS = 3.0D;
    /** Upright eyes must enter the water before vanilla can adopt the swimming pose. */
    private static final double DIVE_TARGET_OFFSET = -0.75D;
    /** Once swimming, travel just below the surface rather than along the bottom. */
    private static final double CRUISE_TARGET_OFFSET = 0.10D;
    private static final double DEPTH_DEADBAND = 0.10D;

    private final IPlayerContext context;
    private final IPath path;
    private Phase phase = Phase.OFF;
    private int surfaceY;
    private boolean waitForAirRefill;

    public SubmergedWaterTravelPolicy(IPlayerContext context, IPath path) {
        this.context = context;
        this.path = path;
    }

    /** Re-evaluate the physical phase from the current selected path movement. */
    public void update(int pathPosition) {
        LocalPlayer player = context.player();
        List<IMovement> movements = path.movements();
        if (player == null || pathPosition < 0 || pathPosition >= movements.size()) {
            phase = Phase.OFF;
            return;
        }

        if (!player.isInWater()) {
            phase = Phase.OFF;
            waitForAirRefill = false;
            return;
        }

        if (waitForAirRefill) {
            if (player.getAirSupply() < diveAirThreshold(player)) {
                phase = Phase.OFF;
                return;
            }
            waitForAirRefill = false;
        }

        IMovement current = movements.get(pathPosition);
        if (phase == Phase.OFF) {
            int candidateSurfaceY = current.getSrc().getY();
            double selectedRun = safeDeepRunDistance(movements, pathPosition, candidateSurfaceY);
            if (selectedRun < MIN_EFFICIENT_RUN_BLOCKS
                    || player.getAirSupply() < diveAirThreshold(player)) {
                return;
            }
            surfaceY = candidateSurfaceY;
            phase = player.isSwimming() || player.isUnderWater()
                    ? Phase.CRUISING : Phase.DIVING;
            return;
        }

        if (airIsLow(player)) {
            phase = Phase.SURFACING;
            waitForAirRefill = true;
        } else if (phase != Phase.SURFACING
                && safeDeepRunDistance(movements, pathPosition, surfaceY)
                        <= SURFACE_RUNWAY_BLOCKS) {
            // The current route itself proves where deep water ends. Rise on its final water
            // runway so the following shore/step movement receives an ordinary standing body.
            phase = Phase.SURFACING;
        } else if (phase == Phase.DIVING
                && (player.isSwimming() || player.isUnderWater())) {
            phase = Phase.CRUISING;
        }

        if (phase == Phase.SURFACING
                && !player.isEyeInFluid(FluidTags.WATER)
                && player.getY() >= surfaceY) {
            phase = Phase.OFF;
        }
    }

    /** Whether this exact current movement owns the temporary physical depth offset. */
    public boolean controls(int pathPosition, IMovement movement) {
        return phase != Phase.OFF
                && pathPosition >= 0
                && pathPosition < path.movements().size()
                && path.movements().get(pathPosition) == movement;
    }

    /**
     * Surface-lattice movements complete by horizontal cell while cruising. During the rise phase
     * the last water movement remains open until the real eyes and feet have reached the surface.
     */
    public boolean movementReached(int pathPosition, IMovement movement) {
        if (!controls(pathPosition, movement)) return false;
        LocalPlayer player = context.player();
        if (player == null
                || Mth.floor(player.getX()) != movement.getDest().getX()
                || Mth.floor(player.getZ()) != movement.getDest().getZ()) {
            return false;
        }
        return phase != Phase.SURFACING
                || (!player.isEyeInFluid(FluidTags.WATER) && player.getY() >= surfaceY);
    }

    public boolean active() {
        return phase != Phase.OFF;
    }

    /** -1 descends, +1 rises, 0 holds the efficient near-surface swimming layer. */
    public int verticalIntent() {
        LocalPlayer player = context.player();
        if (player == null || phase == Phase.OFF) return 0;
        if (phase == Phase.SURFACING) return 1;
        if (phase == Phase.DIVING) {
            double diveTargetY = surfaceY + DIVE_TARGET_OFFSET;
            if (player.getY() > diveTargetY + DEPTH_DEADBAND) return -1;
            if (player.getY() < diveTargetY - DEPTH_DEADBAND) return 1;
            return 0;
        }
        double targetY = surfaceY + CRUISE_TARGET_OFFSET;
        if (player.getY() > targetY + DEPTH_DEADBAND) return -1;
        if (player.getY() < targetY - DEPTH_DEADBAND) return 1;
        return 0;
    }

    /** Pitch paired with the same visible first-person camera curve used by ordinary travel. */
    public float cameraPitch() {
        if (phase == Phase.DIVING) return 28.0F;
        if (phase == Phase.SURFACING) return -24.0F;
        if (phase == Phase.CRUISING) {
            LocalPlayer player = context.player();
            if (player != null) {
                double targetY = surfaceY + CRUISE_TARGET_OFFSET;
                if (player.getY() > targetY + DEPTH_DEADBAND) return 8.0F;
                if (player.getY() < targetY - DEPTH_DEADBAND) return -8.0F;
            }
        }
        return 0.0F;
    }

    /** Preserve an in-flight swim phase when Baritone splices or trims the same route. */
    public void inheritFrom(SubmergedWaterTravelPolicy previous) {
        if (previous == null) return;
        phase = previous.phase;
        surfaceY = previous.surfaceY;
        waitForAirRefill = previous.waitForAirRefill;
    }

    private double safeDeepRunDistance(
            List<IMovement> movements, int start, int candidateSurfaceY) {
        double distance = 0.0D;
        for (int index = start; index < movements.size(); index++) {
            IMovement movement = movements.get(index);
            if (!safeDeepSurfaceMovement(movement, candidateSurfaceY)) break;
            distance += Math.hypot(
                    movement.getDirection().getX(), movement.getDirection().getZ());
        }
        return distance;
    }

    private boolean safeDeepSurfaceMovement(IMovement movement, int candidateSurfaceY) {
        if (!(movement instanceof MovementTraverse)
                && !(movement instanceof MovementDiagonal)) {
            return false;
        }
        BlockPos src = movement.getSrc();
        BlockPos dest = movement.getDest();
        if (src.getY() != candidateSurfaceY || dest.getY() != candidateSurfaceY) {
            return false;
        }
        if (!safeSurfaceColumn(src) || !safeSurfaceColumn(dest)) return false;
        if (movement instanceof MovementDiagonal) {
            BlockPos cornerA = new BlockPos(src.getX(), candidateSurfaceY, dest.getZ());
            BlockPos cornerB = new BlockPos(dest.getX(), candidateSurfaceY, src.getZ());
            return safeSurfaceColumn(cornerA) && safeSurfaceColumn(cornerB);
        }
        return true;
    }

    /** Two water cells, an open escape surface, and no bubble/harm source in the dive layer. */
    private boolean safeSurfaceColumn(BlockPos surface) {
        BlockPos lower = surface.below();
        BlockPos above = surface.above();
        BlockPos floor = lower.below();
        if (!context.world().hasChunkAt(surface)
                || !context.world().hasChunkAt(lower)
                || !context.world().hasChunkAt(above)
                || !context.world().hasChunkAt(floor)) {
            return false;
        }
        BlockState surfaceState = context.world().getBlockState(surface);
        BlockState lowerState = context.world().getBlockState(lower);
        BlockState aboveState = context.world().getBlockState(above);
        BlockState floorState = context.world().getBlockState(floor);
        return waterWithoutBubble(surfaceState)
                && waterWithoutBubble(lowerState)
                && aboveState.getFluidState().isEmpty()
                && aboveState.getCollisionShape(context.world(), above).isEmpty()
                && !floorState.getFluidState().is(FluidTags.LAVA)
                && !floorState.is(Blocks.MAGMA_BLOCK)
                && !floorState.is(Blocks.SOUL_SAND);
    }

    private static boolean waterWithoutBubble(BlockState state) {
        return state.getFluidState().is(FluidTags.WATER)
                && !state.is(Blocks.BUBBLE_COLUMN);
    }

    private static boolean airIsLow(LocalPlayer player) {
        return player.getAirSupply() <= airReserve(player);
    }

    private static int diveAirThreshold(LocalPlayer player) {
        int maximum = Math.max(1, player.getMaxAirSupply());
        return Math.min(maximum, Math.max(airReserve(player) + 40, maximum * 2 / 3));
    }

    private static int airReserve(LocalPlayer player) {
        return Math.max(40, Math.max(1, player.getMaxAirSupply()) / 4);
    }
}
