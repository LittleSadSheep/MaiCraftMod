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

/** 完整几何材料清单在同一份有效配方快照下竞争，包含支柱以及会被消耗的原生链条。 */
public final class KineticRouteChoiceTest {
    public static void main(String[] args) {
        shortAxialAndTurnedRunsChooseTheirActualLowCostGeometry();
        nearerSourceCanRequireMoreExpensiveTurns();
        longRunAccountsForEveryRelayPostAndChain();
        carriedExpensiveChainDrivesDoNotBecomeFree();
        unknownRecipesHaveFinitePositiveRouteCost();
        mediumLongRunHonorsChainPreferenceWithoutInventingRoutes();
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
        // 中继轮不再 charge 落地轴柱：BOM 只含四轮与消耗链，成本模型随之更新。
        check(selected.plan().bom().equals(Map.of("create:chain_conveyor", 4, "minecraft:chain", 32)),
                "three spans need all four conveyors and thirty-two consumed chains");
        check(selected.plan().placements().size() == 4 && selected.plan().chainLinks().size() == 3,
                "native links are counted separately from the wheels themselves");
        equal(10.0, selected.constructionWork(), "four wheel placements and three linking operations contribute actual work");
        equal(56.611111111111114, selected.materials().materialValueUnits(), "the whole relay BOM is valued by finite recipes");
        equal(58.111111111111114, selected.materials().acquisitionDeficitUnits(), "full batches retain one extra material unit beyond amortized value");
        equal(90.95, selected.total(), "complete long-chain score matches the finite recipe and placement model");
        check(ranked.stream().anyMatch(value -> value.plan().family().equals("shaft_gearbox") && value.total() > selected.total()),
                "long conventional shaft routing remains a genuinely costed competing choice");
        World hill = new World(a, b);
        for (int x = 14; x <= 18; x++) hill.ground.put(x + ":0", 4);
        Plan raised = plans(a, b, hill).stream().filter(plan -> plan.family().equals("elevated_chain_conveyor")).findFirst().orElseThrow();
        // 规划高度不再随地形抬升：同距路线的 BOM 与成本和平地完全一致，实际碰撞由原生执行报告。
        var raisedCost = KineticRouteChoice.rank(List.of(raised), prices(Map.of())).getFirst();
        check(raised.bom().equals(selected.plan().bom()) && raisedCost.total() == selected.total(),
                "known terrain rise no longer changes the planned BOM or cost");
    }
    private static void carriedExpensiveChainDrivesDoNotBecomeFree() {
        Endpoint a = endpoint(0, 1, 0, Direction.UP), b = endpoint(8, 1, 0, Direction.UP);
        var candidates = plans(a, b, new World(a, b)); var ranked = KineticRouteChoice.rank(candidates, prices(Map.of("create:encased_chain_drive", 9)));
        var encased = ranked.stream().filter(value -> value.plan().family().equals("encased_chain_drive")).findFirst().orElseThrow();
        check(encased.plan().bom().equals(Map.of("create:encased_chain_drive", 9)), "carried counterexample uses nine actual bridge blocks");
        equal(21, encased.materials().materialValueUnits(), "nine carried casings and their iron retain opportunity cost");
        equal(0, encased.materials().acquisitionDeficitUnits(), "existing stock only removes the acquisition deficit");
        // 携带贵料不再使桥接方案免费：机会成本保留，更便宜的原生路线照常胜出。
        check(ranked.getFirst().total() < encased.total(),
                "material value keeps the costly carried bridge from winning over cheaper native routes");
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
    private static void mediumLongRunHonorsChainPreferenceWithoutInventingRoutes() {
        Endpoint source = endpoint(0, 1, 0, Direction.UP), target = endpoint(17, 1, 0, Direction.UP);
        var base = prices(Map.of()); var values = new HashMap<>(base.rawUnitValues());
        values.put("minecraft:iron_ingot", 20.0); values.put("minecraft:iron_nugget", 20.0 / 9);
        var expensiveChain = new Snapshot(base.recipes(), values, Map.of(), Set.of(), List.of(), new JsonObject());
        var candidates = plans(source, target, new World(source, target));
        var ranked = KineticRouteChoice.rank(candidates, expensiveChain);
        var shaft = ranked.stream().filter(value -> value.plan().chainLinks().isEmpty()).findFirst().orElseThrow();
        // 复现约十七格的选择：补料估价即便更偏爱逐格铺轴，也不能覆盖已经明确的长跨度锁链偏好。
        check(!ranked.getFirst().plan().chainLinks().isEmpty() && ranked.getFirst().total() > shaft.total()
                        && shaft.deferredForChain(), "a feasible long chain wins without falsifying its higher material estimate");
        var report = KineticRouteChoice.report(ranked);
        check(report.get("long_span_horizontal_blocks").getAsInt() == 12
                        && report.getAsJsonObject("selected").get("total_score").getAsDouble() == ranked.getFirst().total(),
                "selection policy and original economic score are reported separately");
        var conventional = candidates.stream().filter(plan -> plan.chainLinks().isEmpty()).toList();
        var fallback = KineticRouteChoice.rank(conventional, expensiveChain);
        check(fallback.stream().noneMatch(KineticRouteChoice.Scored::deferredForChain),
                "absent or rejected native chain geometry leaves ordinary feasible routes available");
        // 偏好只作用于同一对端点；三格外真有更便宜的动力出口时，不能为了远处的锁链绕远。
        Endpoint near = endpoint(14, 1, 0, Direction.UP); var all = new ArrayList<>(candidates);
        all.addAll(plans(near, target, new World(near, target)));
        check(KineticRouteChoice.rank(all, expensiveChain).getFirst().plan().source().equals(near),
                "a nearby economical outlet is not displaced by a farther preferred chain family");
    }
    private static Snapshot prices(Map<String, Integer> carried) {
        // 已安装的 Create 6.0.10 合成倍率；商品锚点是注入的相对单位，不代表通用市场价格。
        var recipes = Map.of(
                "create:shaft", List.of(recipe("shaft", 8, ingredient("create:andesite_alloy", 2))),
                "create:cogwheel", List.of(recipe("cogwheel", 1, ingredient("create:shaft", 1), ingredient("minecraft:oak_planks", 1))),
                "create:large_cogwheel", List.of(recipe("large_cogwheel", 1, ingredient("create:shaft", 1), ingredient("minecraft:oak_planks", 2))),
                "create:gearbox", List.of(recipe("gearbox", 1, ingredient("create:andesite_casing", 1), ingredient("create:cogwheel", 4)),
                        new Recipe("create:crafting/kinetics/gearbox_from_conversion", "create:gearbox", 1,
                                List.of(ingredient("create:vertical_gearbox", 1)), "installed_crafting_recipe", false)),
                "create:vertical_gearbox", List.of(new Recipe("create:crafting/kinetics/vertical_gearbox_from_conversion", "create:vertical_gearbox", 1,
                        List.of(ingredient("create:gearbox", 1)), "installed_crafting_recipe", false)),
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
