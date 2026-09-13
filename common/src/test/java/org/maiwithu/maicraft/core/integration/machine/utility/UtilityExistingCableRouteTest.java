// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.utility;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public final class UtilityExistingCableRouteTest {
    private static int checks;
    public static void main(String[] args) {
        findsAnExactIsolatedRetryPath();
        stopsAtCableEndpointsAndPreservesTheDeclaredFace();
        freshAndReusableInputsIgnoreOtherCityOutlets();
        rejectsBranchesLoopsAndForeignDevices();
        unloadedCellsAreNeverRead();
        gapsAndAbsentCablesHaveNoReusablePath();
        boundsTheWholeInspection();
        System.out.println("UtilityExistingCableRouteTest: " + checks + " checks passed");
    }
    private static void findsAnExactIsolatedRetryPath() {
        World world = straight(5); BlockPos source = BlockPos.ZERO, target = new BlockPos(6, 0, 0);
        var route = UtilityExistingCableRoute.find(source, List.of(Direction.EAST), target, Direction.WEST, world);
        check(route != null && route.sourceFace() == Direction.EAST, "retry keeps the already built source face");
        check(route.path().getFirst().equals(source) && route.path().getLast().equals(target), "route retains the exact source and target");
        check(route.cables().size() == 5 && new HashSet<>(route.cables()).equals(world.cables), "all five existing cables are reused exactly once");
        for (int i = 1; i < route.path().size(); i++) check(route.path().get(i).distManhattan(route.path().get(i - 1)) == 1, "existing path is face contiguous");
        check(world.cables.size() == 5 && world.devices.isEmpty(), "recognition never changes the world fixture");
        check(world.stateReads == world.checked.size(), "each observed cell is classified once after a loaded check");
    }
    private static void stopsAtCableEndpointsAndPreservesTheDeclaredFace() {
        World world = straight(5); BlockPos target = new BlockPos(6, 0, 0);
        world.cables.add(BlockPos.ZERO); world.cables.add(target);
        world.devices.add(target.east()); world.devices.add(BlockPos.ZERO.west());
        var route = UtilityExistingCableRoute.find(BlockPos.ZERO, List.of(Direction.EAST), target, Direction.WEST, world);
        check(route.cables().size() == 5 && !world.checked.contains(target.east()) && !world.checked.contains(BlockPos.ZERO.west()),
                "endpoint cables are boundaries, so their private networks are not traversed");
        World wrongTarget = straight(5); wrongTarget.cables.add(target.north()); wrongTarget.cables.add(target.west().north());
        rejects(() -> UtilityExistingCableRoute.find(BlockPos.ZERO, List.of(Direction.EAST), target, Direction.NORTH, wrongTarget), "unexpected_endpoint_face");
        rejects(() -> UtilityExistingCableRoute.find(BlockPos.ZERO, List.of(Direction.NORTH), target, Direction.WEST, world), "unexpected_endpoint_face");
        World wrongSource = straight(5);
        wrongSource.cables.addAll(List.of(new BlockPos(1, 0, 1), new BlockPos(0, 0, 1)));
        rejects(() -> UtilityExistingCableRoute.find(BlockPos.ZERO, List.of(Direction.EAST, Direction.SOUTH), target, Direction.WEST, wrongSource), "unexpected_endpoint_face");
    }
    private static void rejectsBranchesLoopsAndForeignDevices() {
        BlockPos target = new BlockPos(6, 0, 0);
        World branch = straight(5); branch.cables.add(new BlockPos(3, 1, 0));
        rejects(() -> find(branch, target), "branch");
        World loop = straight(5); loop.cables.addAll(List.of(new BlockPos(2, 1, 0), new BlockPos(3, 1, 0)));
        rejects(() -> find(loop, target), "loop");
        World foreign = straight(5); foreign.devices.add(new BlockPos(3, 0, 1));
        rejects(() -> find(foreign, target), "foreign_device");
    }
    private static void freshAndReusableInputsIgnoreOtherCityOutlets() {
        BlockPos target = new BlockPos(6, 0, 0);
        World fresh = new World(); fresh.cables.add(BlockPos.ZERO.east()); fresh.cables.add(BlockPos.ZERO.east().above());
        fresh.devices.add(new BlockPos(2, 0, 0)); fresh.unloaded.add(new BlockPos(1, -1, 0));
        check(UtilityExistingCableRoute.find(BlockPos.ZERO, List.of(Direction.EAST, Direction.NORTH), target, Direction.WEST, fresh) == null,
                "an empty input approach does not validate or reject existing city consumers");
        check(fresh.checked.equals(Set.of(target.west())), "fresh input reads only its own adjoining cell");
        World reuse = straight(5); reuse.cables.add(BlockPos.ZERO.west()); reuse.cables.add(BlockPos.ZERO.west().above());
        reuse.devices.add(new BlockPos(-2, 0, 0)); reuse.unloaded.add(new BlockPos(-1, -1, 0));
        var route = UtilityExistingCableRoute.find(BlockPos.ZERO, List.of(Direction.WEST, Direction.EAST), target, Direction.WEST, reuse);
        check(route != null && route.cables().size() == 5 && route.sourceFace() == Direction.EAST,
                "retry reuses the target-side path without traversing another eligible city outlet");
        check(!reuse.loadedChecks.contains(BlockPos.ZERO.west()) && !reuse.checked.contains(new BlockPos(-2, 0, 0)),
                "the city source remains the inspection boundary regardless of its other networks");
    }
    private static void unloadedCellsAreNeverRead() {
        World gap = straight(5); gap.unloaded.add(new BlockPos(3, 0, 0));
        rejects(() -> find(gap, new BlockPos(6, 0, 0)), "unloaded");
        check(!gap.checked.contains(new BlockPos(3, 0, 0)), "missing cable chunk is never read to complete a route");
        World side = straight(5); side.unloaded.add(new BlockPos(3, 0, 1));
        rejects(() -> find(side, new BlockPos(6, 0, 0)), "unloaded");
        check(!side.checked.contains(new BlockPos(3, 0, 1)), "unknown adjacent devices are not assumed absent");
        World endpoint = straight(5); endpoint.unloaded.add(BlockPos.ZERO);
        rejects(() -> find(endpoint, new BlockPos(6, 0, 0)), "unloaded");
        check(endpoint.stateReads == 0, "unloaded endpoint fails before cable or device inspection");
    }
    private static void gapsAndAbsentCablesHaveNoReusablePath() {
        World gap = straight(5); gap.cables.remove(new BlockPos(3, 0, 0));
        check(find(gap, new BlockPos(6, 0, 0)) == null, "a simple unfinished segment cannot claim the whole hookup exists");
        World absent = new World();
        check(find(absent, new BlockPos(6, 0, 0)) == null, "no old route lets the caller consider ordinary air-only construction");
        check(UtilityExistingCableRoute.find(BlockPos.ZERO, List.of(), new BlockPos(6, 0, 0), Direction.WEST, absent) == null,
                "no authorized source face yields no reusable path");
    }
    private static void boundsTheWholeInspection() {
        World maximum = straight(128);
        check(find(maximum, new BlockPos(129, 0, 0)).cables().size() == 128, "exactly 128 existing cables stay within the retry budget");
        World excessive = straight(129);
        rejects(() -> find(excessive, new BlockPos(130, 0, 0)), "network_too_large");
        check(excessive.stateReads <= 6 * 128, "large networks terminate with bounded reads");
    }
    private static UtilityConnectionPlanner.Route find(World world, BlockPos target) {
        return UtilityExistingCableRoute.find(BlockPos.ZERO, List.of(Direction.EAST), target, Direction.WEST, world);
    }
    private static World straight(int count) {
        World world = new World(); for (int x = 1; x <= count; x++) world.cables.add(new BlockPos(x, 0, 0)); return world;
    }
    private static final class World implements UtilityExistingCableRoute.WorldView {
        final Set<BlockPos> cables = new HashSet<>(), devices = new HashSet<>(), unloaded = new HashSet<>(), loadedChecks = new HashSet<>(), checked = new HashSet<>();
        int stateReads;
        public boolean loaded(BlockPos at) { loadedChecks.add(at.immutable()); return !unloaded.contains(at); }
        public boolean cable(BlockPos at) {
            if (!loadedChecks.contains(at) || unloaded.contains(at)) throw new AssertionError("cable read before loaded check");
            checked.add(at.immutable()); stateReads++; return cables.contains(at);
        }
        public boolean device(BlockPos at) {
            if (!loadedChecks.contains(at) || unloaded.contains(at)) throw new AssertionError("device read before loaded check");
            return devices.contains(at);
        }
    }
    private static void rejects(Runnable action, String suffix) {
        try { action.run(); throw new AssertionError("unsafe existing route accepted"); }
        catch (UtilityExistingCableRoute.RejectedRouteException expected) {
            check(expected.getMessage().equals("utility_existing_cable_" + suffix), "expected " + suffix + " but got " + expected.getMessage());
        }
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
