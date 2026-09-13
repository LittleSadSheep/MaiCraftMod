// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.core.integration.create.transmission.KineticRouteGeometry.*;

public final class KineticCogwheelGeometryTest {
    private static int checks;
    public static void main(String[] args) {
        auditedMeshRulesAndSigns();
        radialTakeoffWorksWhenBothSourceAxlesAreOccupied();
        diagonalRatiosAndPerpendicularLargeGears();
        gearboxSignsAreIncludedInTheNetRatio();
        meshingCanFeedAnElevatedRelay();
        protectedAndForeignMeshNeighborsStayUntouched();
        System.out.println("KineticCogwheelGeometryTest: " + checks + " checks passed");
    }
    private static void auditedMeshRulesAndSigns() {
        check(mesh("cogwheel", Direction.Axis.Y, "cogwheel", Direction.Axis.Y, 1, 0, 0) == -1, "parallel small gears mesh at a perpendicular side with opposite 1:1 rotation");
        check(mesh("cogwheel", Direction.Axis.Y, "cogwheel", Direction.Axis.X, 1, 0, 0) == 0, "small gears cannot invent a perpendicular-axis mesh");
        check(mesh("cogwheel", Direction.Axis.Y, "cogwheel", Direction.Axis.Y, 0, 1, 0) == 0, "an axial connection is not reported as radial meshing");
        check(mesh("large_cogwheel", Direction.Axis.Y, "cogwheel", Direction.Axis.Y, 1, 0, 1) == -2, "large-to-small diagonal mesh doubles and reverses RPM");
        check(mesh("cogwheel", Direction.Axis.Y, "large_cogwheel", Direction.Axis.Y, -1, 0, 1) == -.5, "small-to-large diagonal mesh halves and reverses RPM");
        check(mesh("large_cogwheel", Direction.Axis.Y, "cogwheel", Direction.Axis.Y, 1, 0, 0) == 0, "large/small cogs do not mesh at a merely adjacent side");
        check(mesh("large_cogwheel", Direction.Axis.Y, "large_cogwheel", Direction.Axis.X, 1, 1, 0) == 1, "perpendicular large gears with same-sign axis offsets preserve signed RPM");
        check(mesh("large_cogwheel", Direction.Axis.Y, "large_cogwheel", Direction.Axis.X, -1, 1, 0) == -1, "opposite-sign perpendicular offsets reverse signed RPM");
        check(mesh("large_cogwheel", Direction.Axis.Y, "large_cogwheel", Direction.Axis.Y, 1, 0, 1) == 0, "parallel large gears do not borrow the small-gear diagonal rule");
        check(mesh("large_cogwheel", Direction.Axis.Y, "large_cogwheel", Direction.Axis.X, 2, 1, 0) == 0, "native propagation neighborhood cannot be stretched to a distant mesh");
    }
    private static void radialTakeoffWorksWhenBothSourceAxlesAreOccupied() {
        Endpoint source = gear("cogwheel"), target = shaft(1, 8, 0, Direction.DOWN); World world = new World(source, target);
        world.solids.add(source.position().above()); world.solids.add(source.position().below());
        var plans = KineticRouteGeometry.generate(source, target, world, Limits.defaults(16));
        Plan mesh = plans.stream().filter(p -> p.family().equals("cog_mesh_small_to_small/gear_shaft")).findFirst().orElseThrow();
        check(mesh.sourceFace() == null && !mesh.source().chainInterface(), "the preserved source is a radial gear interface, not a fictitious free axial face or conveyor");
        check(mesh.bom().equals(Map.of("create:cogwheel", 1, "create:shaft", 3)), "one actual takeoff gear and its three outgoing shafts are charged");
        check(mesh.transmissionRatio() == -1 && mesh.targetFace() == Direction.DOWN, "net ratio retains mesh reversal and the exact target face");
        check(mesh.transmissionJson().getAsJsonArray("gear_meshes").size() == 1 && !mesh.transmissionJson().get("native_connection_verified").getAsBoolean(),
                "the explicit geometric mesh receipt does not claim live native connectivity");
        check(plans.size() <= KineticRouteGeometry.MAX_CANDIDATES, "radial expansion stays within the common candidate budget");
    }
    private static void diagonalRatiosAndPerpendicularLargeGears() {
        Endpoint large = gear("large_cogwheel"), small = gear("cogwheel"), vertical = shaft(1, 8, 1, Direction.DOWN);
        Plan speedup = find(large, vertical, "cog_mesh_large_to_small/gear_shaft");
        check(speedup.transmissionRatio() == -2 && speedup.bom().get("create:cogwheel") == 1, "a real diagonal small takeoff provides its audited -2 ratio");
        Plan reduction = find(small, vertical, "cog_mesh_small_to_large/gear_shaft");
        check(reduction.transmissionRatio() == -.5 && reduction.bom().get("create:large_cogwheel") == 1, "a real diagonal large takeoff provides its audited -0.5 reduction");
        Plan same = find(large, shaft(5, 5, 0, Direction.WEST), "cog_mesh_large_perpendicular/gear_shaft");
        Plan opposite = find(large, shaft(-5, 5, 0, Direction.EAST), "cog_mesh_large_perpendicular/gear_shaft");
        check(same.transmissionRatio() == 1 && opposite.transmissionRatio() == -1, "perpendicular large-gear candidates retain the actual offset-dependent direction");
    }
    private static void gearboxSignsAreIncludedInTheNetRatio() {
        Endpoint source = shaft(0, 4, 0, Direction.EAST), target = shaft(2, 4, 0, Direction.WEST);
        Plan through = new Plan("fixture", source, Direction.EAST, target, Direction.WEST,
                List.of(new Placement(new BlockPos(1, 4, 0), "create:gearbox", Map.of("axis", "y"))), List.of(), Map.of("create:gearbox", 1));
        check(through.transmissionRatio() == -1, "opposite gearbox faces reverse even though both outside shafts share the same axis");
        Endpoint cornerTarget = shaft(1, 4, 1, Direction.NORTH);
        Plan corner = new Plan("fixture", source, Direction.EAST, cornerTarget, Direction.NORTH, through.placements(), List.of(), through.bom());
        check(corner.transmissionRatio() == 1, "perpendicular gearbox input/output with unlike axis directions preserves signed RPM");
        Endpoint isolated = shaft(5, 4, 0, Direction.WEST);
        Plan broken = new Plan("fixture", source, Direction.EAST, isolated, Direction.WEST, through.placements(), List.of(), through.bom());
        check(broken.transmissionRatio() == null, "disconnected geometric declarations cannot produce a fabricated net RPM ratio");
    }
    private static void meshingCanFeedAnElevatedRelay() {
        Endpoint source = gear("cogwheel"), target = shaft(40, 4, 0, Direction.UP);
        Plan relay = find(source, target, "cog_mesh_small_to_small/elevated_chain_conveyor");
        check(!relay.chainLinks().isEmpty() && relay.bom().get("create:cogwheel") == 1 && relay.transmissionRatio() != null,
                "the same real takeoff can feed costed conveyor wheels, posts and native chain links");
    }
    private static void protectedAndForeignMeshNeighborsStayUntouched() {
        Endpoint source = gear("cogwheel"), target = shaft(1, 8, 0, Direction.DOWN); World world = new World(source, target);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) if (x != 0 || z != 0) world.protectedCells.add(source.position().offset(x, 0, z));
        check(KineticCogwheelGeometry.candidates(source, target, world, Limits.defaults(16)).isEmpty(), "protected radial takeoff cells are never substituted for blocked source axles");
        World foreign = new World(source, target); foreign.kinetics.add(new BlockPos(2, 4, 0));
        var candidates = KineticCogwheelGeometry.candidates(source, target, foreign, Limits.defaults(16));
        check(candidates.stream().noneMatch(p -> p.placements().getFirst().position().equals(new BlockPos(1, 4, 0))), "a takeoff cannot silently mesh with or touch a second existing kinetic network");
    }
    private static Plan find(Endpoint source, Endpoint target, String family) {
        return KineticRouteGeometry.generate(source, target, new World(source, target), Limits.defaults(16)).stream()
                .filter(p -> p.family().equals(family)).findFirst().orElseThrow(() -> new AssertionError("missing " + family));
    }
    private static double mesh(String a, Direction.Axis axisA, String b, Direction.Axis axisB, int x, int y, int z) { return KineticTransmissionRatios.mesh(a, axisA, b, axisB, new BlockPos(x, y, z)); }
    private static Endpoint gear(String family) { return new Endpoint(new BlockPos(0, 4, 0), Direction.Axis.Y, List.of(Direction.UP, Direction.DOWN), family); }
    private static Endpoint shaft(int x, int y, int z, Direction face) { return new Endpoint(new BlockPos(x, y, z), face.getAxis(), List.of(face), "shaft"); }
    private static final class World implements Terrain {
        final Set<BlockPos> solids = new HashSet<>(), kinetics = new HashSet<>(), protectedCells = new HashSet<>(), loadedChecks = new HashSet<>();
        World(Endpoint source, Endpoint target) { solids.add(source.position()); solids.add(target.position()); kinetics.addAll(solids); }
        public boolean loaded(BlockPos at) { loadedChecks.add(at.immutable()); return at.getY() >= 0 && at.getY() < 64; }
        public boolean passable(BlockPos at) { read(at); return at.getY() > 0 && !solids.contains(at); }
        public boolean protectedCell(BlockPos at) { read(at); return protectedCells.contains(at); }
        public boolean kinetic(BlockPos at) { read(at); return kinetics.contains(at); }
        public Integer groundHeight(int x, int z) { return 0; }
        private void read(BlockPos at) { if (!loadedChecks.contains(at) || at.getY() < 0 || at.getY() >= 64) throw new AssertionError("unknown block read"); }
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
