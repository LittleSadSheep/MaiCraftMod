// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.core.integration.create.transmission.KineticRouteGeometry.*;

/** Straight horizontal chain-drive bridges between exact parallel vertical shaft interfaces. */
final class KineticEncasedGeometry {
    private KineticEncasedGeometry() {}
    static Plan candidate(Endpoint source, Direction sourceFace, Endpoint target, Direction targetFace, Terrain terrain, Limits limits) {
        if (sourceFace == null || targetFace == null || sourceFace.getAxis() != Direction.Axis.Y || targetFace.getAxis() != Direction.Axis.Y) return null;
        BlockPos a = source.position().relative(sourceFace), b = target.position().relative(targetFace);
        if (a.getY() != b.getY() || a.equals(b) || a.getX() != b.getX() && a.getZ() != b.getZ()) return null;
        int distance = a.distManhattan(b); if (distance + 1 > limits.maxPlacements()) return null;
        Direction.Axis axis = a.getX() != b.getX() ? Direction.Axis.X : Direction.Axis.Z;
        Direction step = Direction.fromAxisAndDirection(axis,
                axis.choose(b.getX() - a.getX(), 0, b.getZ() - a.getZ()) < 0 ? Direction.AxisDirection.NEGATIVE : Direction.AxisDirection.POSITIVE);
        KineticGeometryWork work = new KineticGeometryWork(source, sourceFace, target, targetFace, terrain, limits);
        for (int i = 0; i <= distance; i++) {
            BlockPos at = a.relative(step, i);
            work.put(at, "create:encased_chain_drive", Map.of("axis", "y", "axis_along_first", Boolean.toString(axis == Direction.Axis.X)));
            if (i > 0) work.join(at.relative(step.getOpposite()), at);
        }
        work.join(source.position(), a); work.join(b, target.position());
        return work.finish("encased_chain_drive");
    }
}
