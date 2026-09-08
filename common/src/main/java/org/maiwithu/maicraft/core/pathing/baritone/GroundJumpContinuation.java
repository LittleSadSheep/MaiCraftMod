package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementTraverse;

/** The horizontal route layer of one explicitly verified travel hop, never a generic air override. */
public final class GroundJumpContinuation {
    private Integer floor;
    private double takeoffY;
    private boolean airborne;
    private int ticks;
    private java.util.Set<IMovement> verified = java.util.Set.of();

    public void launch(int floor, double takeoffY, java.util.List<IMovement> runway) {
        this.floor = floor; this.takeoffY = takeoffY; airborne = false; ticks = 0;
        verified = java.util.Set.copyOf(runway);
    }

    public void observe(boolean grounded, double y) {
        if (floor == null) return;
        if (!grounded) airborne = true;
        // Preserve ordinary recovery for falling below the runway, missed launches, and landing.
        if (grounded && airborne || y < takeoffY - 0.1 || ++ticks > (airborne ? 40 : 3)) floor = null;
    }

    public BetterBlockPos feet(IMovement movement, BetterBlockPos actual) {
        if (floor == null || movement == null || !verified.contains(movement)
                || !(movement instanceof MovementTraverse || movement instanceof MovementDiagonal)
                || movement.getSrc().getY() != floor || movement.getDest().getY() != floor) return actual;
        return new BetterBlockPos(actual.x, floor, actual.z);
    }
}
