// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.core.integration.create.transmission.KineticRouteGeometry.*;

/** Native-independent checks of physical axes, filled posts, loaded-world isolation and exact candidate BOMs. */
public final class KineticRouteGeometryTest {
    private static int checks;
    public static void main(String[] args) {
        straightShaftsCompeteWithConveyorsAtTheSameDistance();
        orthogonalTurnsUseTheUnusedGearboxAxis();
        existingGearAxlesAreRetained();
        elevatedRelayPostsAndLinksHaveExactMaterialCounts();
        knownTerrainDeterminesClearanceAndPostHeight();
        existingConveyorIsAReadOnlyChainBoundary();
        obstaclesProtectionAndUnknownTerrainNeverBecomePermission();
        limitsAndCancellationStayExplicit();
        System.out.println("KineticRouteGeometryTest: " + checks + " checks passed");
    }
    private static void straightShaftsCompeteWithConveyorsAtTheSameDistance() {
        Endpoint source = endpoint(0, 1, 0, Direction.EAST), target = endpoint(8, 1, 0, Direction.WEST);
        List<Plan> plans = generate(source, target, new World(source, target), 16);
        Plan shaft = plans.stream().filter(p -> p.family().equals("axial_shaft")).findFirst().orElseThrow();
        check(shaft.placements().size() == 7 && shaft.bom().equals(Map.of("create:shaft", 7)), "axial candidate uses seven shafts and no casing or invisible materials");
        check(shaft.placements().stream().allMatch(p -> p.properties().equals(Map.of("axis", "x"))), "every straight shaft follows the actual native axis");
        check(plans.stream().anyMatch(p -> p.family().equals("elevated_chain_conveyor")), "short-distance native conveyor remains a costed alternative instead of a distance cutoff");
        check(plans.stream().noneMatch(p -> p.placements().stream().anyMatch(b -> b.blockId().contains("creative"))), "no candidate invents a generator");
        check(shaft.blueprint(new BlockPos(10, 0, 0)).getAsJsonArray("blocks").get(0).getAsJsonObject().getAsJsonArray("offset").get(0).getAsInt() == -9,
                "explicit blueprint export respects its anchor and actual coordinates");
        verifyMaterials(plans);
    }
    private static void orthogonalTurnsUseTheUnusedGearboxAxis() {
        Endpoint source = endpoint(0, 1, 0, Direction.EAST), target = endpoint(6, 1, 6, Direction.NORTH);
        var plans = generate(source, target, new World(source, target), 16);
        check(plans.stream().anyMatch(p -> p.family().equals("shaft_gearbox") && p.bom().getOrDefault("create:gearbox", 0) == 1),
                "an X-to-Z corner can use one gearbox rather than a chain drive per block");
        Plan corner = plans.stream().filter(p -> p.family().equals("shaft_gearbox") && p.bom().getOrDefault("create:gearbox", 0) == 1).findFirst().orElseThrow();
        check(corner.placements().stream().filter(p -> p.blockId().equals("create:gearbox")).allMatch(p -> p.properties().get("axis").equals("y")),
                "X/Z shafts meet a Y-axis gearbox, whose available shaft faces lie in the X/Z plane");
        check(corner.sourceFace() == Direction.EAST && corner.targetFace() == Direction.NORTH, "a cheap route cannot change either observed interface");
        verifyMaterials(plans);
    }
    private static void existingGearAxlesAreRetained() {
        Endpoint source = new Endpoint(new BlockPos(0, 1, 0), Direction.Axis.X, List.of(Direction.EAST), "large_cogwheel");
        Endpoint target = endpoint(8, 1, 0, Direction.WEST);
        Plan route = generate(source, target, new World(source, target), 16).stream().filter(p -> p.family().equals("gear_shaft")).findFirst().orElseThrow();
        check(route.bom().equals(Map.of("create:shaft", 7)), "an existing aligned gear axle requires neither a replacement gear nor a made-up reducer");
        check(route.placements().stream().noneMatch(p -> p.position().equals(source.position()) || p.position().equals(target.position())), "existing endpoints are excluded from all placement targets");
    }
    private static void elevatedRelayPostsAndLinksHaveExactMaterialCounts() {
        Endpoint source = endpoint(0, 1, 0, Direction.UP), target = endpoint(40, 1, 0, Direction.UP);
        var plans = generate(source, target, new World(source, target), 16);
        check(plans.stream().anyMatch(p -> p.family().equals("shaft_gearbox")), "long distance also retains a shaft alternative for real recipe costing");
        Plan relay = chain(plans);
        check(relay.chainLinks().size() == 3 && relay.bom().get("create:chain_conveyor") == 4, "strict native length limit splits 40 blocks into three real links and four wheels");
        check(relay.bom().get("create:shaft") == 10, "two terminal posts and two full intermediate posts are charged, not only their top shafts");
        check(relay.bom().get("minecraft:chain") == 16, "each native link costs round(distance/2.5), then link costs are summed");
        verifyFilledPosts(relay, 0);
        for (ChainLink link : relay.chainLinks()) {
            check(Math.sqrt(link.from().distSqr(link.to())) < 16, "client-native strict maximum link span is respected");
            check(link.from().getY() == 4 && link.to().getY() == 4, "flat terrain gives exactly three clear blocks below the elevated chain");
        }
        Plan tighter = chain(generate(source, target, new World(source, target), 12));
        check(tighter.chainLinks().size() > relay.chainLinks().size() && tighter.bom().get("create:shaft") > relay.bom().get("create:shaft"),
                "a smaller native chain limit increases real posts and their material cost");
        check(relay.linksJson().size() == relay.chainLinks().size() && !relay.blueprint(BlockPos.ZERO).toString().contains("minecraft:chain"),
                "native chain interactions are exported separately from physical block targets");
        verifyMaterials(plans);
    }
    private static void knownTerrainDeterminesClearanceAndPostHeight() {
        Endpoint source = endpoint(0, 1, 0, Direction.UP), target = endpoint(40, 1, 0, Direction.UP);
        World terrain = new World(source, target);
        for (int x = 14; x <= 18; x++) terrain.ground.put(column(x, 0), 4);
        Plan relay = chain(generate(source, target, terrain, 16));
        check(relay.chainLinks().stream().allMatch(link -> link.from().getY() == 8 && link.to().getY() == 8), "a known four-block rise raises the aerial route just enough for three-block clearance");
        check(relay.bom().get("create:shaft") == 26, "every added vertical shaft caused by terrain height appears in the BOM");
        check(relay.placements().stream().mapToInt(p -> p.position().getY()).max().orElseThrow() == 8, "no arbitrary giant tower is inserted above the required height");
    }
    private static void existingConveyorIsAReadOnlyChainBoundary() {
        Endpoint source = new Endpoint(new BlockPos(0, 4, 0), Direction.Axis.Y, List.of(), "chain_conveyor");
        Endpoint target = endpoint(40, 1, 0, Direction.UP);
        Plan relay = chain(generate(source, target, new World(source, target), 16));
        check(relay.sourceFace() == null && relay.chainLinks().getFirst().from().equals(source.position()), "explicit chain-interface reuse links the observed wheel directly");
        check(relay.bom().get("create:chain_conveyor") == 3 && relay.placements().stream().noneMatch(p -> p.position().equals(source.position())),
                "existing city conveyor is preserved and excluded from materials");
        check(relay.chainLinks().stream().allMatch(link -> link.from().getY() == 4 && link.to().getY() == 4), "existing correctly elevated wheel does not force an unnecessary taller tower");
    }
    private static void obstaclesProtectionAndUnknownTerrainNeverBecomePermission() {
        Endpoint source = endpoint(0, 1, 0, Direction.EAST), target = endpoint(8, 1, 0, Direction.WEST);
        World protectedWorld = new World(source, target); protectedWorld.protectedCells.add(source.position().east());
        check(generate(source, target, protectedWorld, 16).isEmpty(), "all alternatives respect the protected exact source approach");
        World unloaded = new World(source, target); unloaded.unloaded.add(source.position().east());
        check(generate(source, target, unloaded, 16).isEmpty(), "an unknown exact approach never becomes a provisional executable route");
        check(!unloaded.readCells.contains(source.position().east()), "unloaded block data is never read");
        World foreign = new World(source, target); foreign.kinetic.add(source.position().east().above());
        check(generate(source, target, foreign, 16).isEmpty(), "no gearbox or shaft candidate silently joins an unrelated neighboring kinetic device");
        World unknownGround = new World(source, target); unknownGround.unknownColumns.add(column(2, 0));
        var plans = generate(source, target, unknownGround, 16);
        check(plans.stream().anyMatch(p -> p.family().equals("axial_shaft")) && plans.stream().noneMatch(p -> p.family().equals("elevated_chain_conveyor")),
                "unknown ground rejects invented supports while retaining an independently observed shaft route");
        Endpoint farSource = endpoint(0, 1, 0, Direction.UP), farTarget = endpoint(40, 1, 0, Direction.UP);
        World corridor = new World(farSource, farTarget); corridor.protectedCells.add(new BlockPos(5, 4, 1));
        check(generate(farSource, farTarget, corridor, 16).stream().noneMatch(p -> p.family().equals("elevated_chain_conveyor")),
                "a protected chain span is respected even where no physical wheel block would be placed");
    }
    private static void limitsAndCancellationStayExplicit() {
        Endpoint source = endpoint(0, 1, 0, Direction.EAST), target = endpoint(8, 1, 0, Direction.WEST);
        try { new Endpoint(source.position(), Direction.Axis.Y, List.of(Direction.EAST), "shaft"); throw new AssertionError("wrong shaft axis accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { KineticRouteGeometry.generate(source, target, new World(source, target), new Limits(16, 3, 512, 4)); throw new AssertionError("unbounded route accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        check(generate(source, target, new World(source, target), 3).stream().noneMatch(p -> p.family().equals("elevated_chain_conveyor")),
                "too-small native span is not enlarged to make conveyor geometry pass");
        Thread.currentThread().interrupt();
        try { generate(source, target, new World(source, target), 16); throw new AssertionError("interrupted geometry accepted"); }
        catch (java.util.concurrent.CancellationException expected) { checks++; }
        finally { Thread.interrupted(); }
    }
    private static void verifyFilledPosts(Plan plan, int ground) {
        Map<BlockPos, Placement> blocks = new HashMap<>(); plan.placements().forEach(p -> blocks.put(p.position(), p));
        for (Placement wheel : plan.placements()) if (wheel.blockId().equals("create:chain_conveyor")) {
            int bottom = wheel.position().getX() == plan.source().position().getX() && wheel.position().getZ() == plan.source().position().getZ()
                    ? plan.source().position().getY() + 1 : wheel.position().getX() == plan.target().position().getX() && wheel.position().getZ() == plan.target().position().getZ()
                    ? plan.target().position().getY() + 1 : ground + 1;
            for (int y = bottom; y < wheel.position().getY(); y++) {
                Placement support = blocks.get(new BlockPos(wheel.position().getX(), y, wheel.position().getZ()));
                check(support != null && support.blockId().equals("create:shaft") && support.properties().get("axis").equals("y"), "relay supports contain every physical vertical shaft");
            }
        }
    }
    private static void verifyMaterials(List<Plan> plans) {
        for (Plan plan : plans) {
            Map<String, Integer> expected = new HashMap<>(); plan.placements().forEach(p -> expected.merge(p.blockId(), 1, Integer::sum));
            plan.chainLinks().forEach(link -> expected.merge("minecraft:chain", (int) Math.max(1, Math.round(Math.sqrt(link.from().distSqr(link.to())) / 2.5)), Integer::sum));
            check(expected.equals(plan.bom()), "candidate BOM is exactly its real block placements plus native chain charges");
            check(plan.placements().stream().map(Placement::position).distinct().count() == plan.placements().size(), "a physical cell appears only once in materials and construction");
        }
    }
    private static Plan chain(List<Plan> plans) { return plans.stream().filter(p -> p.family().equals("elevated_chain_conveyor")).findFirst().orElseThrow(() -> new AssertionError("no elevated chain candidate: " + plans)); }
    private static List<Plan> generate(Endpoint source, Endpoint target, World terrain, int span) { return KineticRouteGeometry.generate(source, target, terrain, Limits.defaults(span)); }
    private static Endpoint endpoint(int x, int y, int z, Direction face) { return new Endpoint(new BlockPos(x, y, z), face.getAxis(), List.of(face), "shaft"); }
    private static String column(int x, int z) { return x + ":" + z; }
    private static final class World implements Terrain {
        final Set<BlockPos> endpoints = new HashSet<>(), kinetic = new HashSet<>(), protectedCells = new HashSet<>(), unloaded = new HashSet<>(), checked = new HashSet<>(), readCells = new HashSet<>();
        final Set<String> knownColumns = new HashSet<>(), unknownColumns = new HashSet<>();
        final Map<String, Integer> ground = new HashMap<>();
        World(Endpoint source, Endpoint target) { endpoints.add(source.position()); endpoints.add(target.position()); kinetic.addAll(endpoints); }
        public boolean loaded(BlockPos at) { checked.add(at.immutable()); if (!unloaded.contains(at)) knownColumns.add(column(at.getX(), at.getZ())); return !unloaded.contains(at); }
        public boolean passable(BlockPos at) { requireLoaded(at); return at.getY() > ground.getOrDefault(column(at.getX(), at.getZ()), 0) && !endpoints.contains(at); }
        public boolean protectedCell(BlockPos at) { requireLoaded(at); return protectedCells.contains(at); }
        public boolean kinetic(BlockPos at) { requireLoaded(at); return kinetic.contains(at); }
        public Integer groundHeight(int x, int z) {
            if (!knownColumns.contains(column(x, z))) throw new AssertionError("ground queried in an unknown column");
            return unknownColumns.contains(column(x, z)) ? null : ground.getOrDefault(column(x, z), 0);
        }
        private void requireLoaded(BlockPos at) { if (!checked.contains(at) || unloaded.contains(at)) throw new AssertionError("unloaded state read"); readCells.add(at.immutable()); }
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
