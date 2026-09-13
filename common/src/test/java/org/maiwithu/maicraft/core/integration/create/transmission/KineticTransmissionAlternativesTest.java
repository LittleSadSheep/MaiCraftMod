// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.core.integration.create.transmission.KineticRouteGeometry.*;

public final class KineticTransmissionAlternativesTest {
    private static int checks;
    public static void main(String[] args) {
        encasedBridgeCompetesWithTheOtherRealPlans();
        encasedGeometryRejectsUnprovenTurnsAndMerges();
        wheelFootprintsAndBothStrandsNeedClearance();
        slopedSweepsAndUnknownCellsAreCheckedContinuously();
        System.out.println("KineticTransmissionAlternativesTest: " + checks + " checks passed");
    }
    private static void encasedBridgeCompetesWithTheOtherRealPlans() {
        Endpoint source = shaft(0, 1, 0, Direction.UP), target = shaft(2, 1, 0, Direction.UP);
        var plans = KineticRouteGeometry.generate(source, target, new World(source, target), Limits.defaults(16));
        Plan encased = plans.stream().filter(p -> p.family().equals("encased_chain_drive")).findFirst().orElseThrow();
        check(encased.bom().equals(Map.of("create:encased_chain_drive", 3)) && encased.chainLinks().isEmpty(),
                "the short encased alternative quotes exactly three physical chain-drive blocks, without native conveyor-link charges");
        check(encased.placements().stream().allMatch(p -> p.properties().equals(Map.of("axis", "y", "axis_along_first", "true"))),
                "each X bridge drive has a vertical shaft and the matching horizontal chain axis");
        check(plans.stream().anyMatch(p -> p.family().equals("shaft_gearbox")), "AUTO keeps shaft/gearbox and encased alternatives for the same cost comparison");
        Endpoint zTarget = shaft(0, 1, 3, Direction.UP);
        Plan z = KineticEncasedGeometry.candidate(source, Direction.UP, zTarget, Direction.UP, new World(source, zTarget), Limits.defaults(16));
        check(z != null && z.bom().get("create:encased_chain_drive") == 4 && z.placements().stream().allMatch(p -> p.properties().get("axis_along_first").equals("false")),
                "a Z bridge uses the other native chain orientation, not a fictitious corner");
    }
    private static void encasedGeometryRejectsUnprovenTurnsAndMerges() {
        Endpoint source = shaft(0, 1, 0, Direction.UP), diagonal = shaft(3, 1, 3, Direction.UP), high = shaft(4, 2, 0, Direction.UP);
        check(KineticEncasedGeometry.candidate(source, Direction.UP, diagonal, Direction.UP, new World(source, diagonal), Limits.defaults(16)) == null,
                "diagonal endpoints do not produce an unsupported one-layer chain turn");
        check(KineticEncasedGeometry.candidate(source, Direction.UP, high, Direction.UP, new World(source, high), Limits.defaults(16)) == null,
                "different approach heights require another audited routing family");
        Endpoint horizontal = shaft(4, 1, 0, Direction.WEST);
        check(KineticEncasedGeometry.candidate(source, Direction.UP, horizontal, Direction.WEST, new World(source, horizontal), Limits.defaults(16)) == null,
                "a horizontal shaft is not connected to a chain drive's decorative chain side");
        Endpoint target = shaft(3, 1, 0, Direction.UP); World foreign = new World(source, target); foreign.kinetics.add(new BlockPos(1, 2, 1));
        check(KineticEncasedGeometry.candidate(source, Direction.UP, target, Direction.UP, foreign, Limits.defaults(16)) == null,
                "the short encased candidate cannot silently join neighboring kinetic machinery");
    }
    private static void wheelFootprintsAndBothStrandsNeedClearance() {
        Endpoint source = wheel(0, 4, 0), target = wheel(10, 4, 0);
        World empty = new World(source, target);
        check(clear(source, target, empty), "isolated existing wheel boundaries have a clear native chain envelope");
        World rim = new World(source, target); rim.solids.add(source.position().offset(1, 0, 1));
        check(!clear(source, target, rim), "the 1.25-radius rotating wheel cannot clip a diagonal adjacent solid block");
        check(rim.read.contains(source.position().offset(1, 0, 1)), "wheel footprint checks go beyond its center block");
        for (int z : new int[] {-1, 1}) {
            World strand = new World(source, target); strand.solids.add(new BlockPos(5, 4, z));
            check(!clear(source, target, strand), "both offset native tangent strands are checked in their interior, not only the wheel endpoints");
        }
        World protectedStrand = new World(source, target); protectedStrand.protectedCells.add(new BlockPos(5, 4, 1));
        check(!clear(source, target, protectedStrand), "protected mid-span cells cannot be crossed by invisible chain interactions");
    }
    private static void slopedSweepsAndUnknownCellsAreCheckedContinuously() {
        Endpoint source = wheel(0, 4, 0), target = wheel(12, 8, 0);
        check(KineticRouteGeometry.validLink(source.position(), target.position(), 16), "test slope satisfies native chain geometry");
        World clear = new World(source, target); check(clear(source, target, clear), "clear sloped native strands remain a valid candidate");
        World blocked = new World(source, target); blocked.solids.add(new BlockPos(6, 6, 1));
        check(!clear(source, target, blocked), "sweep boxes include the intermediate layer crossed by an ascending strand");
        World unknown = new World(source, target); BlockPos missing = new BlockPos(6, 6, 1); unknown.unloaded.add(missing);
        check(!clear(source, target, unknown) && !unknown.read.contains(missing), "unloaded mid-span cells fail without reading or forcing the missing chunk");
    }
    private static boolean clear(Endpoint source, Endpoint target, World world) {
        return KineticChainClearance.clear(new KineticGeometryWork(source, null, target, null, world, Limits.defaults(16)), List.of(source.position(), target.position()));
    }
    private static Endpoint shaft(int x, int y, int z, Direction face) { return new Endpoint(new BlockPos(x, y, z), face.getAxis(), List.of(face), "shaft"); }
    private static Endpoint wheel(int x, int y, int z) { return new Endpoint(new BlockPos(x, y, z), Direction.Axis.Y, List.of(), "chain_conveyor"); }
    private static final class World implements Terrain {
        final Set<BlockPos> solids = new HashSet<>(), protectedCells = new HashSet<>(), unloaded = new HashSet<>(), kinetics = new HashSet<>(), checked = new HashSet<>(), read = new HashSet<>();
        World(Endpoint source, Endpoint target) { solids.add(source.position()); solids.add(target.position()); kinetics.addAll(solids); }
        public boolean loaded(BlockPos at) { checked.add(at.immutable()); return !unloaded.contains(at); }
        public boolean passable(BlockPos at) { requireLoaded(at); return at.getY() > 0 && !solids.contains(at); }
        public boolean protectedCell(BlockPos at) { requireLoaded(at); return protectedCells.contains(at); }
        public boolean kinetic(BlockPos at) { requireLoaded(at); return kinetics.contains(at); }
        public Integer groundHeight(int x, int z) { return 0; }
        private void requireLoaded(BlockPos at) { if (!checked.contains(at) || unloaded.contains(at)) throw new AssertionError("unloaded block read"); read.add(at.immutable()); }
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
