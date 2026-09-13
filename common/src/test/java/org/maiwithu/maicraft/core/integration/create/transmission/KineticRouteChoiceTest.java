// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import static org.maiwithu.maicraft.core.integration.create.transmission.KineticMaterialCosts.*;
import static org.maiwithu.maicraft.core.integration.create.transmission.KineticRouteGeometry.*;

/** Full geometric BOMs compete under the same effective recipe snapshot, including posts and consumed native chains. */
public final class KineticRouteChoiceTest {
    public static void main(String[] args) {
        shortAxialAndTurnedRunsChooseTheirActualLowCostGeometry();
        nearerSourceCanRequireMoreExpensiveTurns();
        longRunAccountsForEveryRelayPostAndChain();
        carriedExpensiveChainDrivesDoNotBecomeFree();
        unknownRecipesHaveFinitePositiveRouteCost();
    }
    private static void shortAxialAndTurnedRunsChooseTheirActualLowCostGeometry() {
        Endpoint a = endpoint(0, 1, 0, Direction.EAST), b = endpoint(8, 1, 0, Direction.WEST);
        var straight = KineticRouteChoice.rank(plans(a, b, new World(a, b)), prices(Map.of())).getFirst();
        check(straight.plan().family().equals("axial_shaft") && straight.plan().bom().equals(Map.of("create:shaft", 7)),
                "eight-block axial separation needs seven ordinary shafts, not new chain terminals");
        equal(1.75, straight.materials().materialValueUnits(), "seven shafts retain the installed 2-alloy/8-output recipe ratio");
        equal(2, straight.materials().acquisitionDeficitUnits(), "one complete eight-shaft batch is acquired");
        equal(9.85, straight.total(), "short route score includes its seven placements and actual span");
        Endpoint corner = endpoint(4, 1, 4, Direction.NORTH);
        var turned = KineticRouteChoice.rank(plans(a, corner, new World(a, corner)), prices(Map.of())).getFirst();
        check(turned.plan().family().equals("shaft_gearbox") && turned.plan().bom().equals(Map.of("create:shaft", 6, "create:gearbox", 1)),
                "short X/Z route charges one real gearbox and six shafts");
        equal(5.5, turned.materials().materialValueUnits(), "gearbox recipe includes four cogwheels and one casing");
        equal(7, turned.materials().acquisitionDeficitUnits(), "gearbox and final shafts share batches without losing output-count rounding");
    }
    private static void nearerSourceCanRequireMoreExpensiveTurns() {
        Endpoint far = endpoint(0, 1, 0, Direction.EAST), near = endpoint(8, 1, 3, Direction.UP);
        Endpoint target = endpoint(8, 1, 0, Direction.WEST); World world = new World(far, near, target);
        List<Plan> candidates = new ArrayList<>(plans(near, target, world));
        check(!candidates.isEmpty(), "near source must have feasible alternatives, not be excluded to force the expected choice");
        candidates.addAll(plans(far, target, world));
        var ranked = KineticRouteChoice.rank(candidates, prices(Map.of()));
        check(near.position().distSqr(target.position()) < far.position().distSqr(target.position()), "test sources actually differ in distance");
        check(ranked.getFirst().plan().source().equals(far) && ranked.getFirst().plan().family().equals("axial_shaft"),
                "the farther aligned outlet beats the nearer outlet's terminal conversions and three-dimensional turns");
        check(ranked.stream().filter(value -> value.plan().source().equals(near)).allMatch(value -> value.total() > ranked.getFirst().total()),
                "winner has lower total cost than every feasible nearer-source alternative");
    }
    private static void longRunAccountsForEveryRelayPostAndChain() {
        Endpoint a = endpoint(0, 1, 0, Direction.UP), b = endpoint(80, 1, 0, Direction.UP);
        var candidates = plans(a, b, new World(a, b)); var ranked = KineticRouteChoice.rank(candidates, prices(Map.of()));
        var selected = ranked.getFirst();
        check(selected.plan().family().equals("elevated_chain_conveyor"), "an eighty-block run benefits from native long links after full material and work costing");
        check(selected.plan().bom().equals(Map.of("create:chain_conveyor", 4, "create:shaft", 10, "minecraft:chain", 32)),
                "three spans need all four conveyors, ten vertical support shafts and thirty-two consumed chains");
        check(selected.plan().placements().size() == 14 && selected.plan().chainLinks().size() == 3,
                "native links are counted separately from their physically built posts");
        equal(20, selected.constructionWork(), "four complete posts and three linking operations contribute actual work");
        equal(59 + 1.0 / 9, selected.materials().materialValueUnits(), "whole relay BOM, not only its endpoint wheels, is valued");
        equal(60 + 1.0 / 9, selected.materials().acquisitionDeficitUnits(), "full batches retain one extra material unit beyond amortized value");
        equal(104.15, selected.total(), "complete long-chain score matches the finite recipe and placement model");
        check(ranked.stream().anyMatch(value -> value.plan().family().equals("shaft_gearbox") && value.total() > selected.total()),
                "long conventional shaft routing remains a genuinely costed competing choice");
        World hill = new World(a, b);
        for (int x = 14; x <= 18; x++) hill.ground.put(x + ":0", 4);
        Plan raised = plans(a, b, hill).stream().filter(plan -> plan.family().equals("elevated_chain_conveyor")).findFirst().orElseThrow();
        var raisedCost = KineticRouteChoice.rank(List.of(raised), prices(Map.of())).getFirst();
        check(raised.bom().get("create:shaft") == 26, "terrain rise adds sixteen actual shaft cells across the four posts");
        equal(4, raisedCost.materials().materialValueUnits() - selected.materials().materialValueUnits(), "all additional post shafts affect material value");
        equal(16, raisedCost.constructionWork() - selected.constructionWork(), "additional height also costs sixteen real placements");
        check(raisedCost.total() > selected.total(), "raised support cost is never hidden by the unchanged horizontal source distance");
    }
    private static void carriedExpensiveChainDrivesDoNotBecomeFree() {
        Endpoint a = endpoint(0, 1, 0, Direction.UP), b = endpoint(8, 1, 0, Direction.UP);
        var candidates = plans(a, b, new World(a, b)); var ranked = KineticRouteChoice.rank(candidates, prices(Map.of("create:encased_chain_drive", 9)));
        var encased = ranked.stream().filter(value -> value.plan().family().equals("encased_chain_drive")).findFirst().orElseThrow();
        check(encased.plan().bom().equals(Map.of("create:encased_chain_drive", 9)), "carried counterexample uses nine actual bridge blocks");
        equal(21, encased.materials().materialValueUnits(), "nine carried casings and their iron retain opportunity cost");
        equal(0, encased.materials().acquisitionDeficitUnits(), "existing stock only removes the acquisition deficit");
        check(ranked.getFirst().plan().family().equals("shaft_gearbox") && ranked.getFirst().total() < encased.total(),
                "material value prevents free-stock bias toward the costly encased bridge");
    }
    private static void unknownRecipesHaveFinitePositiveRouteCost() {
        Endpoint a = endpoint(0, 1, 0, Direction.EAST), b = endpoint(8, 1, 0, Direction.WEST);
        Plan shaft = plans(a, b, new World(a, b)).stream().filter(plan -> plan.family().equals("axial_shaft")).findFirst().orElseThrow();
        Snapshot missing = new Snapshot(Map.of(), Map.of(), Map.of(), Set.of("create:shaft"), List.of(), new JsonObject());
        var cost = KineticRouteChoice.rank(List.of(shaft), missing).getFirst();
        check(Double.isFinite(cost.total()) && cost.total() > 0 && cost.materials().materialValueUnits() > 0
                && cost.materials().acquisitionDeficitUnits() > 0 && cost.materials().hasUnknowns(), "missing recipe data receives explicit finite positive estimates");
        var report = KineticRouteChoice.report(List.of(cost));
        check(!report.get("globally_optimal").getAsBoolean() && !report.getAsJsonObject("selected").get("production_verified").getAsBoolean(),
                "economic ranking never becomes a native connection or production claim");
    }
    private static Snapshot prices(Map<String, Integer> carried) {
        // Installed Create 6.0.10 crafting ratios; commodity anchors are injected relative units, not universal prices.
        var recipes = Map.of(
                "create:shaft", List.of(recipe("shaft", 8, ingredient("create:andesite_alloy", 2))),
                "create:cogwheel", List.of(recipe("cogwheel", 1, ingredient("create:shaft", 1), ingredient("minecraft:oak_planks", 1))),
                "create:large_cogwheel", List.of(recipe("large_cogwheel", 1, ingredient("create:shaft", 1), ingredient("minecraft:oak_planks", 2))),
                "create:gearbox", List.of(recipe("gearbox", 1, ingredient("create:andesite_casing", 1), ingredient("create:cogwheel", 4))),
                "create:chain_conveyor", List.of(recipe("chain_conveyor", 2, ingredient("create:andesite_casing", 4), ingredient("create:large_cogwheel", 1))),
                "create:encased_chain_drive", List.of(recipe("encased_chain_drive", 1, ingredient("create:andesite_casing", 1), ingredient("minecraft:iron_nugget", 3))),
                "minecraft:chain", List.of(new Recipe("minecraft:chain", "minecraft:chain", 1,
                        List.of(ingredient("minecraft:iron_ingot", 1), ingredient("minecraft:iron_nugget", 2)), "installed_crafting_recipe", false)));
        return new Snapshot(recipes, Map.of("create:andesite_alloy", 1.0, "create:andesite_casing", 2.0,
                "minecraft:oak_planks", .25, "minecraft:iron_ingot", 1.0, "minecraft:iron_nugget", 1.0 / 9), carried, Set.of(), List.of(), new JsonObject());
    }
    private static Recipe recipe(String name, int output, Ingredient... inputs) { return new Recipe("create:crafting/kinetics/" + name, "create:" + name, output, List.of(inputs), "installed_crafting_recipe", false); }
    private static Ingredient ingredient(String item, int count) { return new Ingredient(List.of(item), count); }
    private static Endpoint endpoint(int x, int y, int z, Direction face) { return new Endpoint(new BlockPos(x, y, z), face.getAxis(), List.of(face), "shaft"); }
    private static List<Plan> plans(Endpoint source, Endpoint target, World world) { return KineticRouteGeometry.generate(source, target, world, Limits.defaults(32)); }
    private static final class World implements Terrain {
        final Set<BlockPos> endpoints = new HashSet<>(); final Map<String, Integer> ground = new HashMap<>();
        World(Endpoint... values) { for (Endpoint value : values) endpoints.add(value.position()); }
        public boolean loaded(BlockPos position) { return true; }
        public boolean passable(BlockPos position) { return position.getY() > groundHeight(position.getX(), position.getZ()) && !endpoints.contains(position); }
        public boolean protectedCell(BlockPos position) { return false; }
        public boolean kinetic(BlockPos position) { return endpoints.contains(position); }
        public Integer groundHeight(int x, int z) { return ground.getOrDefault(x + ":" + z, 0); }
    }
    private static void equal(double expected, double actual, String message) { check(Math.abs(expected - actual) < 1e-9, message + "; expected=" + expected + " actual=" + actual); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
