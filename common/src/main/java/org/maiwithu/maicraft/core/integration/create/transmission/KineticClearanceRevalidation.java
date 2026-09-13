// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.integration.create.transmission.KineticRouteGeometry.*;

/** Current swept-volume checks accept only air or exactly matching owned targets, including their real post height. */
final class KineticClearanceRevalidation {
    private static final int MAX_GROUND_SCAN = 256;
    private KineticClearanceRevalidation() {}
    static boolean valid(Plan plan, Terrain terrain, int clearance) {
        if (plan == null || terrain == null || clearance < 1 || clearance > 16 || plan.placements().size() > 4096) return false;
        if (!terrain.loaded(plan.source().position()) || !terrain.loaded(plan.target().position())) return false;
        Map<BlockPos, Placement> planned = new LinkedHashMap<>(); Set<BlockPos> owned = new HashSet<>();
        for (Placement placement : plan.placements()) {
            KineticRouteGeometry.checkpoint(); BlockPos at = placement.position();
            if (planned.putIfAbsent(at, placement) != null || !terrain.loaded(at) || terrain.protectedCell(at)) return false;
            if (terrain.matches(placement)) owned.add(at);
            else if (!terrain.passable(at) || terrain.kinetic(at)) return false;
        }
        Terrain remaining = new RemainingTerrain(terrain, planned, owned);
        if (!plan.chainLinks().isEmpty()) {
            var work = new KineticGeometryWork(plan.source(), plan.sourceFace(), plan.target(), plan.targetFace(), remaining,
                    new Limits(1024, clearance, Math.max(1, plan.placements().size()), 512));
            work.blocks.putAll(planned);
            // Individual explicit links may form a path without matching insertion-order adjacency.
            for (ChainLink link : plan.chainLinks()) if (!KineticChainClearance.clear(work, java.util.List.of(link.from(), link.to()))) return false;
        }
        return KineticCogwheelGeometry.clearanceValid(plan, remaining);
    }
    private static final class RemainingTerrain implements Terrain {
        private final Terrain original;
        private final Map<BlockPos, Placement> planned;
        private final Set<BlockPos> owned;
        private final Map<Long, Integer> ground = new HashMap<>();
        RemainingTerrain(Terrain original, Map<BlockPos, Placement> planned, Set<BlockPos> owned) {
            this.original = original; this.planned = planned; this.owned = owned;
        }
        public boolean loaded(BlockPos at) { return original.loaded(at); }
        public boolean passable(BlockPos at) { return owned.contains(at) || original.passable(at); }
        public boolean protectedCell(BlockPos at) { return original.protectedCell(at); }
        public boolean kinetic(BlockPos at) { return !owned.contains(at) && original.kinetic(at); }
        public boolean matches(Placement p) { return owned.contains(p.position()) && p.equals(planned.get(p.position())); }
        public Integer groundHeight(int x, int z) {
            long key = ((long) x << 32) ^ (z & 0xffffffffL); if (ground.containsKey(key)) return ground.get(key);
            Integer top = original.groundHeight(x, z); if (top == null) return null;
            for (int steps = 0; steps < MAX_GROUND_SCAN; steps++, top--) {
                BlockPos at = new BlockPos(x, top, z);
                if (!original.loaded(at)) return null;
                if (owned.contains(at) || original.passable(at)) continue;
                ground.put(key, top); return top;
            }
            return null;
        }
    }
}
