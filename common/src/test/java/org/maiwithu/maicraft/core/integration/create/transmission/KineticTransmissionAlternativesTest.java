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
        chainSpansDoNotRequireEmptyCells();
        slopedLinksDoNotReadIntermediateChunks();
        existingLowWheelsUseTheirActualHeight();
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
        var encased = KineticEncasedGeometry.candidate(source, Direction.UP, target, Direction.UP, foreign, Limits.defaults(16));
        // 候选不得把外来传动装置当成路线元素；高度规则改变后仍要显式核对放置与链接两端。
        check(encased != null && encased.placements().stream().noneMatch(b -> b.position().equals(new BlockPos(1, 2, 1)))
                        && encased.chainLinks().stream().noneMatch(l -> l.from().equals(new BlockPos(1, 2, 1)) || l.to().equals(new BlockPos(1, 2, 1))),
                "the short encased candidate cannot silently join neighboring kinetic machinery");
    }
    private static void chainSpansDoNotRequireEmptyCells() {
        Endpoint source = wheel(0, 4, 0), target = wheel(10, 4, 0);
        World empty = new World(source, target);
        check(clear(source, target, empty), "isolated existing wheel boundaries have a clear native chain envelope");
        World rim = new World(source, target); rim.solids.add(source.position().offset(1, 0, 1));
        // 轮缘是显示几何，邻接石块不影响原生挂链；这里不额外读取或要求清空它们。
        check(clear(source, target, rim), "native link accepts solid blocks beside the rendered wheel rim");
        check(!rim.read.contains(source.position().offset(1, 0, 1)), "rendered wheel rim does not require adjacent block inspection");
        for (int z : new int[] {-1, 1}) {
            World strand = new World(source, target); strand.solids.add(new BlockPos(5, 4, z));
            check(clear(source, target, strand), "native links do not require empty tangent strands");
        }
        World protectedStrand = new World(source, target); protectedStrand.protectedCells.add(new BlockPos(5, 4, 1));
        check(clear(source, target, protectedStrand), "linking endpoints does not mutate protected mid-span cells");
    }
    private static void slopedLinksDoNotReadIntermediateChunks() {
        Endpoint source = wheel(0, 4, 0), target = wheel(12, 8, 0);
        check(KineticRouteGeometry.validLink(source.position(), target.position(), 16), "test slope satisfies native chain geometry");
        World clear = new World(source, target); check(clear(source, target, clear), "clear sloped native strands remain a valid candidate");
        World blocked = new World(source, target); blocked.solids.add(new BlockPos(6, 6, 1));
        // 倾斜链条仍只操作两个端点，中间地形不是施工格，也不需要强制加载。
        check(clear(source, target, blocked), "sloped native links do not require empty intermediate layers");
        World unknown = new World(source, target); BlockPos missing = new BlockPos(6, 6, 1); unknown.unloaded.add(missing);
        check(clear(source, target, unknown) && !unknown.read.contains(missing), "unloaded mid-span cells are not read or force-loaded");
    }
    private static boolean clear(Endpoint source, Endpoint target, World world) {
        return KineticChainClearance.clear(new KineticGeometryWork(source, null, target, null, world, Limits.defaults(16)), List.of(source.position(), target.position()));
    }
    private static void existingLowWheelsUseTheirActualHeight() {
        // 两只现有轮在地面上方两格，旁边还留着施工踏步；这些低处方块不与轮缘或链条占用体积相交。
        Endpoint source=wheel(0,2,0), target=wheel(10,2,0); World world=new World(source,target);
        world.solids.add(new BlockPos(9,0,1));
        var direct=KineticRouteGeometry.generate(source,target,world,Limits.defaults(16)).stream()
                .filter(plan->plan.family().equals("existing_chain_conveyor_link")).findFirst().orElseThrow();
        check(direct.placements().isEmpty() && direct.bom().equals(Map.of("minecraft:chain",4)),
                "existing low wheels need chains only, not a replacement pair and taller shaft pillars");
        check(KineticRouteGeometry.clearanceValid(direct,world),"live revalidation preserves the same existing-wheel clearance rule");
        // 屋顶高度和链条经过的石块均不改变原生端点连接，不为它们额外架高现有轮。
        world.ground=8;
        check(clear(source,target,world),"overhead heightmap does not obstruct an observed empty chain envelope");
        world.solids.add(new BlockPos(5,2,1));
        check(clear(source,target,world) && KineticRouteGeometry.clearanceValid(direct,world),
                "occupied mid-span cells do not invalidate the existing pair");
        check(!KineticRouteGeometry.validLink(source.position(),new BlockPos(1,2,0),16),"native minimum distance remains required");
    }
    private static Endpoint shaft(int x, int y, int z, Direction face) { return new Endpoint(new BlockPos(x, y, z), face.getAxis(), List.of(face), "shaft"); }
    private static Endpoint wheel(int x, int y, int z) { return new Endpoint(new BlockPos(x, y, z), Direction.Axis.Y, List.of(), "chain_conveyor"); }
    private static final class World implements Terrain {
        int ground;
        final Set<BlockPos> solids = new HashSet<>(), protectedCells = new HashSet<>(), unloaded = new HashSet<>(), kinetics = new HashSet<>(), checked = new HashSet<>(), read = new HashSet<>();
        World(Endpoint source, Endpoint target) { solids.add(source.position()); solids.add(target.position()); kinetics.addAll(solids); }
        public boolean loaded(BlockPos at) { checked.add(at.immutable()); return !unloaded.contains(at); }
        public boolean passable(BlockPos at) { requireLoaded(at); return at.getY() > 0 && !solids.contains(at); }
        public boolean protectedCell(BlockPos at) { requireLoaded(at); return protectedCells.contains(at); }
        public boolean kinetic(BlockPos at) { requireLoaded(at); return kinetics.contains(at); }
        public Integer groundHeight(int x, int z) { return ground; }
        private void requireLoaded(BlockPos at) { if (!checked.contains(at) || unloaded.contains(at)) throw new AssertionError("unloaded block read"); read.add(at.immutable()); }
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
