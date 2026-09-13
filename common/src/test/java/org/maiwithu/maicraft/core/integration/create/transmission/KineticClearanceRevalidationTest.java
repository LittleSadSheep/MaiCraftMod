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

public final class KineticClearanceRevalidationTest {
    private static int checks;
    public static void main(String[] args) {
        matchingBuiltTowersDoNotBecomeNewTerrain();
        changedBlocksAndNewObstaclesCannotBeAdopted();
        radialLargeGearFootprintsAreRechecked();
        System.out.println("KineticClearanceRevalidationTest: " + checks + " checks passed");
    }
    private static void matchingBuiltTowersDoNotBecomeNewTerrain() {
        Endpoint source = shaft(0, 1, 0, Direction.EAST), target = shaft(8, 1, 0, Direction.WEST); World world = new World(source, target);
        Plan plan = KineticRouteGeometry.generate(source, target, world, Limits.defaults(16)).stream().filter(p -> p.family().equals("elevated_chain_conveyor")).findFirst().orElseThrow();
        check(KineticRouteGeometry.clearanceValid(plan, world), "current empty space validates before construction");
        world.install(plan);
        check(world.groundHeight(2, 0) == 4 && world.groundHeight(1, 0) == 1, "fixture heightmap sees the new wheel top and its horizontal adapter shaft");
        check(KineticRouteGeometry.clearanceValid(plan, world), "matching own wheels, full posts and terminal adapters are excluded when reconstructing terrain height");
        check(world.blocks.size() == plan.placements().size() + 2, "clearance revalidation never clears, rotates or reconstructs those blocks");
    }
    private static void changedBlocksAndNewObstaclesCannotBeAdopted() {
        Endpoint source = shaft(0, 1, 0, Direction.UP), target = shaft(40, 1, 0, Direction.UP); World world = new World(source, target);
        Plan plan = KineticRouteGeometry.generate(source, target, world, Limits.defaults(16)).stream().filter(p -> p.family().equals("elevated_chain_conveyor")).findFirst().orElseThrow();
        world.install(plan); check(KineticRouteGeometry.clearanceValid(plan, world), "long completed relay retains its initial clearance");
        BlockPos post = new BlockPos(13, 2, 0); Placement correct = world.blocks.get(post);
        world.blocks.put(post, new Placement(post, "create:shaft", Map.of("axis", "x")));
        check(!KineticRouteGeometry.clearanceValid(plan, world), "an incorrect shaft orientation is not accepted as an owned post");
        world.blocks.put(post, correct);
        BlockPos rim = new BlockPos(13, 4, 1); world.wall(rim);
        check(!KineticRouteGeometry.clearanceValid(plan, world), "a wall added beside a built wheel invalidates its rotating footprint");
        world.blocks.remove(rim);
        BlockPos strand = new BlockPos(5, 4, 1); world.wall(strand);
        check(!KineticRouteGeometry.clearanceValid(plan, world), "ordinary non-kinetic blocks added inside an unbuilt chain span are caught");
        world.blocks.remove(strand); world.protectedCells.add(strand);
        check(!KineticRouteGeometry.clearanceValid(plan, world), "newly protected chain space prevents the native link action");
        world.protectedCells.clear(); world.unloaded.add(strand); world.read.remove(strand);
        check(!KineticRouteGeometry.clearanceValid(plan, world) && !world.read.contains(strand), "missing span chunks are never loaded or read to finish validation");
    }
    private static void radialLargeGearFootprintsAreRechecked() {
        Endpoint source = new Endpoint(new BlockPos(0, 4, 0), Direction.Axis.Y, List.of(Direction.UP, Direction.DOWN), "cogwheel");
        Endpoint target = shaft(1, 8, 1, Direction.DOWN); World world = new World(source, target);
        Plan plan = KineticRouteGeometry.generate(source, target, world, Limits.defaults(16)).stream().filter(p -> p.family().equals("cog_mesh_small_to_large/gear_shaft")).findFirst().orElseThrow();
        world.install(plan); check(KineticRouteGeometry.clearanceValid(plan, world), "a matched built radial takeoff is revalidated without pretending it is air in the real world");
        world.wall(plan.placements().getFirst().position().east());
        check(!KineticRouteGeometry.clearanceValid(plan, world), "an ordinary block added to the radial large-gear disc blocks use");
    }
    private static Endpoint shaft(int x, int y, int z, Direction face) { return new Endpoint(new BlockPos(x, y, z), face.getAxis(), List.of(face), "shaft"); }
    private static final class World implements Terrain {
        final Map<BlockPos, Placement> blocks = new HashMap<>();
        final Set<BlockPos> checked = new HashSet<>(), read = new HashSet<>(), unloaded = new HashSet<>(), protectedCells = new HashSet<>();
        World(Endpoint source, Endpoint target) {
            blocks.put(source.position(), new Placement(source.position(), "create:" + source.family(), Map.of("axis", source.axis().getName())));
            blocks.put(target.position(), new Placement(target.position(), "create:" + target.family(), Map.of("axis", target.axis().getName())));
        }
        void install(Plan plan) { plan.placements().forEach(p -> blocks.put(p.position(), p)); }
        void wall(BlockPos at) { blocks.put(at, new Placement(at, "minecraft:stone", Map.of())); }
        public boolean loaded(BlockPos at) { checked.add(at.immutable()); return at.getY() >= 0 && at.getY() < 128 && !unloaded.contains(at); }
        public boolean passable(BlockPos at) { read(at); return at.getY() > 0 && !blocks.containsKey(at); }
        public boolean protectedCell(BlockPos at) { read(at); return protectedCells.contains(at); }
        public boolean kinetic(BlockPos at) { read(at); Placement p = blocks.get(at); return p != null && p.blockId().startsWith("create:"); }
        public boolean matches(Placement p) { read(p.position()); return p.equals(blocks.get(p.position())); }
        public Integer groundHeight(int x, int z) { return blocks.keySet().stream().filter(at -> at.getX() == x && at.getZ() == z).mapToInt(BlockPos::getY).max().orElse(0); }
        private void read(BlockPos at) { if (!checked.contains(at) || unloaded.contains(at) || at.getY() < 0 || at.getY() >= 128) throw new AssertionError("unloaded state read"); read.add(at.immutable()); }
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
