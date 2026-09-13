// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.utility;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public final class UtilityConnectionPlannerTest {
    public static void main(String[] args) {
        exactFacesAndProtectedDetour();
        loadedCorridorAndLimits();
        nativePortDirectionsAndStorageViews();
        kineticPowerAndMembership();
        pendingPowerDoesNotBecomeSupplied();
        requirementsRemainExplicit();
        System.out.println("UtilityConnectionPlannerTest: 6 routing and native evidence groups passed");
    }
    private static void exactFacesAndProtectedDetour() {
        BlockPos source = BlockPos.ZERO, target = new BlockPos(6, 0, 0), obstacle = new BlockPos(3, 0, 0);
        var route = UtilityConnectionPlanner.plan(source, List.of(Direction.EAST), target, Direction.NORTH,
                at -> !at.equals(obstacle));
        check(route.path().getFirst().equals(source) && route.path().getLast().equals(target), "selected endpoints must remain exact");
        check(route.path().get(1).equals(source.east()), "route must start on a confirmed export face");
        check(route.path().get(route.path().size() - 2).equals(target.north()), "nearest west face cannot replace declared north input");
        check(!route.path().contains(obstacle), "occupied/protected cells cannot be replaced");
        check(new HashSet<>(route.path()).size() == route.path().size(), "route cannot loop");
        for (int i = 1; i < route.path().size(); i++) check(route.path().get(i).distManhattan(route.path().get(i - 1)) == 1, "every cable edge must be explicit");
        check(!route.cables().contains(source) && !route.cables().contains(target), "endpoint blocks are never part of construction");
        check(route.cables().stream().filter(at -> at.distManhattan(target) == 1).toList().equals(List.of(target.north())),
                "a detour must not incidentally attach another target face before the declared input");
        rejects(() -> UtilityConnectionPlanner.plan(source, List.of(Direction.EAST), target, Direction.NORTH,
                at -> !at.equals(target.north())), "utility_input_approach_occupied_or_unloaded");
    }
    private static void loadedCorridorAndLimits() {
        BlockPos source = BlockPos.ZERO, target = new BlockPos(96, 0, 0);
        var allowed = new HashSet<BlockPos>(); for (int x = 1; x < 96; x++) allowed.add(new BlockPos(x, 0, 0));
        var route = UtilityConnectionPlanner.plan(source, List.of(Direction.EAST), target, Direction.WEST, allowed::contains);
        check(route.cables().size() == 95, "long observed straight corridor must not fail a breadth-first exploration budget");
        allowed.remove(new BlockPos(50, 0, 0));
        rejects(() -> UtilityConnectionPlanner.plan(source, List.of(Direction.EAST), target, Direction.WEST, allowed::contains),
                "utility_no_loaded_preserving_cable_route");
        rejects(() -> UtilityConnectionPlanner.plan(source, List.of(Direction.EAST), target.east(), Direction.WEST, at -> true),
                "utility_route_requires_nearer_outlet");
        rejects(() -> UtilityConnectionPlanner.plan(source, List.of(), new BlockPos(3, 0, 0), Direction.WEST, at -> true),
                "utility_no_loaded_preserving_cable_route");
    }
    private static void nativePortDirectionsAndStorageViews() {
        JsonObject observed = json("{ports:[{api:'energy',medium:'energy',status:'observed',side:'east',input:'disabled',output:'verified'},"
                + "{api:'energy',medium:'energy',status:'observed',side:'west',input:'verified',output:'disabled'},"
                + "{api:'mekanism_energy',medium:'energy',status:'observed',side:'up',input:'unknown',output:'unknown'}],"
                + "resources:[{identity:{id:'neoforge:energy'},side:'east',amount:40},{identity:{id:'neoforge:energy'},side:'east',amount:40},"
                + "{identity:{id:'mekanism:joules'},side:'east',amount:4000},{identity:{id:'neoforge:energy'},side:'west',amount:1000}]}");
        check(UtilityConnectionEvidence.energyFaces(observed, true).equals(List.of(Direction.EAST)), "only native export permission permits source use");
        check(UtilityConnectionEvidence.energyFaces(observed, false).equals(List.of(Direction.WEST)), "an export face is not an input");
        check(UtilityConnectionEvidence.storedEnergy(observed, Direction.EAST) == 40, "overlapping faces/capability units cannot inflate available energy");
        observed.getAsJsonArray("ports").get(0).getAsJsonObject().addProperty("output", "unknown");
        check(UtilityConnectionEvidence.energyFaces(observed, true).isEmpty(), "unknown direction cannot authorize cable construction");
    }
    private static void kineticPowerAndMembership() {
        JsonObject source = json("{native:{create:{hasNetwork:true,isOverStressed:false,getSpeed:64,network_id:'grid-a'}}}");
        JsonObject target = json("{native:{create:{hasNetwork:true,isOverStressed:false,getSpeed:32,network_id:'grid-b'}}}");
        check(UtilityConnectionEvidence.kineticPowered(source, 32), "live non-overstressed source can meet explicit RPM");
        check(!UtilityConnectionEvidence.kineticPowered(source, 128), "speed requirement cannot be inferred from mere rotation");
        check(!UtilityConnectionEvidence.sameKineticNetwork(source, target), "two powered unrelated networks are not a hookup");
        target.getAsJsonObject("native").getAsJsonObject("create").addProperty("network_id", "grid-a");
        check(UtilityConnectionEvidence.sameKineticNetwork(source, target), "exact native membership can corroborate built connection");
        source.getAsJsonObject("native").getAsJsonObject("create").addProperty("isOverStressed", true);
        check(!UtilityConnectionEvidence.kineticPowered(source, 0), "overstress blocks readiness even with a numeric speed");
        source.getAsJsonObject("native").getAsJsonObject("create").remove("isOverStressed");
        check(!UtilityConnectionEvidence.kineticPowered(source, 0), "missing stress evidence is not a successful check");
    }
    private static void requirementsRemainExplicit() {
        rejects(() -> new UtilityConnectionTaskRecord.Request(BlockPos.ZERO, BlockPos.ZERO, Direction.UP, "create:shaft", "kinetic", 0, 0, ""),
                "utility_distinct_endpoints_required");
        rejects(() -> new UtilityConnectionTaskRecord.Request(BlockPos.ZERO, new BlockPos(4, 0, 0), Direction.UP, "create:shaft", "kinetic", Double.NaN, 0, ""),
                "utility_invalid_minimum_rpm");
        rejects(() -> new UtilityConnectionTaskRecord.Request(BlockPos.ZERO, new BlockPos(4, 0, 0), Direction.UP, "create:shaft", "kinetic", 16, 1000, ""),
                "utility_throughput_requirement_unsupported");
    }
    private static void pendingPowerDoesNotBecomeSupplied() {
        var pending = UtilityConnectionEvidence.readiness(true, true, true, false, false);
        check(Boolean.TRUE.equals(pending.get("connection_ready")) && Boolean.TRUE.equals(pending.get("power_pending")),
                "a native cable path with an empty destination is connected while power is pending");
        check(Boolean.FALSE.equals(pending.get("power_ready")) && Boolean.FALSE.equals(pending.get("flow_verified")),
                "topology and source energy cannot certify destination supply or actual transfer");
        check(UtilityConnectionEvidence.completionMessage(false, true, false).contains("still pending"),
                "successful connection message must explicitly retain pending power delivery");
    }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static void rejects(Runnable run, String expected) {
        try { run.run(); throw new AssertionError("request unexpectedly accepted"); }
        catch (IllegalArgumentException failure) { check(failure.getMessage().equals(expected), "expected " + expected + " but got " + failure.getMessage()); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
