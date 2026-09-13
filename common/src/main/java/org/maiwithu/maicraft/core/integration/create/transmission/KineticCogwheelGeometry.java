// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.core.integration.create.transmission.KineticRouteGeometry.*;

/** One audited radial gear takeoff, then an ordinary bounded shaft/gearbox or conveyor alternative. */
final class KineticCogwheelGeometry {
    private record Takeoff(BlockPos at, String family, Direction.Axis axis, String kind) {}
    private KineticCogwheelGeometry() {}
    static List<Plan> candidates(Endpoint source, Endpoint target, Terrain terrain, Limits limits) {
        if (!source.family().equals("cogwheel") && !source.family().equals("large_cogwheel")) return List.of();
        Map<String, List<Plan>> groups = new LinkedHashMap<>();
        for (Takeoff gear : takeoffs(source, target)) {
            KineticRouteGeometry.checkpoint();
            if (!clearGear(source, target, gear, terrain, null)) continue;
            List<Direction> outlets = java.util.Arrays.stream(Direction.values()).filter(face -> face.getAxis() == gear.axis)
                    .sorted(Comparator.comparingInt(face -> gear.at.relative(face).distManhattan(target.position()))).toList();
            Endpoint virtual = new Endpoint(gear.at, gear.axis, outlets, gear.family);
            List<Direction> targetFaces = target.chainInterface() ? java.util.Collections.singletonList(null) : target.shaftFaces();
            for (Direction outlet : outlets) for (Direction inlet : targetFaces) {
                List<Plan> bases = new ArrayList<>();
                if (inlet != null) {
                    bases.addAll(KineticShaftGeometry.candidates(virtual, outlet, target, inlet, terrain, limits));
                    Plan encased = KineticEncasedGeometry.candidate(virtual, outlet, target, inlet, terrain, limits);
                    if (encased != null) bases.add(encased);
                }
                bases.addAll(KineticRelayGeometry.candidates(virtual, outlet, target, inlet, terrain, limits));
                for (Plan base : bases) {
                    String key = gear.kind + '/' + base.family(); List<Plan> bucket = groups.computeIfAbsent(key, ignored -> new ArrayList<>());
                    if (bucket.size() >= 2 || !clearGear(source, target, gear, terrain, base)) continue;
                    List<Placement> blocks = new ArrayList<>(); blocks.add(new Placement(gear.at, "create:" + gear.family, Map.of("axis", gear.axis.getName()))); blocks.addAll(base.placements());
                    Map<String, Integer> bom = new LinkedHashMap<>(base.bom()); bom.merge("create:" + gear.family, 1, Math::addExact);
                    if (blocks.size() > limits.maxPlacements()) continue;
                    Plan plan = new Plan("cog_mesh_" + gear.kind + '/' + base.family(), source, null, target, base.targetFace(), blocks, base.chainLinks(), bom);
                    if (plan.transmissionRatio() == null || bucket.stream().anyMatch(prior -> prior.bom().equals(plan.bom())
                            && prior.transmissionRatio().equals(plan.transmissionRatio()))) continue;
                    bucket.add(plan);
                }
            }
        }
        return groups.values().stream().flatMap(List::stream).limit(24).toList();
    }
    private static List<Takeoff> takeoffs(Endpoint source, Endpoint target) {
        List<Takeoff> result = new ArrayList<>();
        for (String family : List.of("cogwheel", "large_cogwheel")) for (Direction.Axis axis : Direction.Axis.values())
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                BlockPos delta = new BlockPos(x, y, z);
                if (KineticTransmissionRatios.mesh(source.family(), source.axis(), family, axis, delta) == 0) continue;
                String kind = source.family().equals(family) ? family.equals("cogwheel") ? "small_to_small" : "large_perpendicular"
                        : source.family().equals("large_cogwheel") ? "large_to_small" : "small_to_large";
                result.add(new Takeoff(source.position().offset(delta), family, axis, kind));
            }
        result.sort(Comparator.comparingInt((Takeoff gear) -> gear.at.distManhattan(target.position())).thenComparing(Takeoff::kind)
                .thenComparingLong(gear -> gear.at.asLong()));
        return List.copyOf(result);
    }
    private static boolean clearGear(Endpoint source, Endpoint target, Takeoff gear, Terrain terrain, Plan base) {
        if (!terrain.loaded(gear.at) || terrain.protectedCell(gear.at) || !terrain.passable(gear.at) || terrain.kinetic(gear.at)) return false;
        Map<BlockPos, Placement> planned = new LinkedHashMap<>(); if (base != null) base.placements().forEach(p -> planned.put(p.position(), p));
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            BlockPos at = gear.at.offset(dx, dy, dz); if (at.equals(source.position())) continue;
            if (!terrain.loaded(at)) return false;
            Placement next = planned.get(at);
            if (next != null) {
                Direction side = KineticRouteGeometry.between(gear.at, at);
                boolean shaft = side != null && side.getAxis() == gear.axis;
                if (shaft && side != base.sourceFace()) return false;
                if (gear.family.equals("large_cogwheel") && gear.axis.choose(dx, dy, dz) == 0) return false;
                continue;
            }
            if (at.equals(target.position()) || terrain.kinetic(at)) return false;
            if (gear.family.equals("large_cogwheel") && gear.axis.choose(dx, dy, dz) == 0
                    && (terrain.protectedCell(at) || !terrain.passable(at))) return false;
        }
        return true;
    }
}
