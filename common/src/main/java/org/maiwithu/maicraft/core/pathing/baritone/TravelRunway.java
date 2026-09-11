// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.pathing.movement.IMovement;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementTraverse;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** The remaining straight part of the chosen route, independent of its movement segmentation. */
record TravelRunway(Vec3 start, Vec3 heading, List<IMovement> movements) {
    static boolean accepts(IMovement movement) {
        return (movement instanceof MovementTraverse || movement instanceof MovementDiagonal
                || movement instanceof MovementGroundStraight)
                && movement.getSrc().getY() == movement.getDest().getY();
    }

    static TravelRunway capture(List<IMovement> path, int position, Vec3 feet) {
        if (position < 0 || position >= path.size() || !accepts(path.get(position))) return null;
        IMovement first = path.get(position);
        Vec3 origin = first instanceof MovementGroundStraight straight ? straight.origin()
                : Vec3.atBottomCenterOf(first.getSrc());
        Vec3 direction = end(first).subtract(origin).multiply(1, 0, 1).normalize();
        if (direction.lengthSqr() < .99 || !Double.isFinite(feet.lengthSqr())) return null;
        var selected = new ArrayList<IMovement>();
        IMovement previous = null;
        for (int i = position; i < path.size(); i++) {
            IMovement movement = path.get(i);
            if (!accepts(movement) || movement.getSrc().getY() != first.getSrc().getY()) break;
            if (previous != null) {
                if (!previous.getDest().equals(movement.getSrc())) break;
                Vec3 delta = end(movement).subtract(end(previous));
                if (delta.lengthSqr() < 1e-8 || delta.normalize().dot(direction) < 1 - 1e-6) break;
            }
            selected.add(movement);
            previous = movement;
            if (end(movement).subtract(feet).dot(direction) >= GroundCorridor.MAX_LENGTH) break;
        }
        return new TravelRunway(feet, direction, List.copyOf(selected));
    }

    List<IMovement> covering(double distance) {
        if (!Double.isFinite(distance) || distance <= 0) return List.of();
        for (int i = 0; i < movements.size(); i++) {
            if (end(movements.get(i)).subtract(start).dot(heading) >= distance)
                return List.copyOf(movements.subList(0, i + 1));
        }
        return List.of();
    }

    Vec3 point(double distance) { return start.add(heading.scale(distance)); }
    private static Vec3 end(IMovement movement) {
        return movement instanceof MovementGroundStraight straight ? straight.target()
                : Vec3.atBottomCenterOf(movement.getDest());
    }
}
