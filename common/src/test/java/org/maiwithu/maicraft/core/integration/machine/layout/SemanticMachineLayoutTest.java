// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.layout;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Pure construction-graph invariants, with no Minecraft bootstrap or optional mod dependency. */
public final class SemanticMachineLayoutTest {
    private static int checks;
    private static final SemanticMachineLayout.Registry REGISTRY = new SemanticMachineLayout.Registry() {
        public boolean blockExists(String id) { return true; }
        public boolean itemExists(String id) { return true; }
        public boolean supportsState(String id, Map<String, String> properties) { return true; }
    };
    public static void main(String[] args) {
        compilesConnectedEquipmentAndKeepsMaintenanceAccess();
        compilesMixedResourceRoutes();
        compilesActualKineticTurns();
        emitsActualAeCenterParts();
        failsClosedForUnprovenInterfacesAndMissingDependencies();
        constrainsDimensionsAndNeverClaimsProduction();
        deterministicAndBoundedLargeLayout();
        interruptedCompilationDoesNotProduceAPlan();
        System.out.println("SemanticMachineLayoutTest: " + checks + " checks passed");
    }

    private static void compilesConnectedEquipmentAndKeepsMaintenanceAccess() {
        JsonObject design = graph(new String[][] {{"input", "minecraft:chest"}, {"smelter", "mekanism:energized_smelter"},
                {"battery", "mekanism:basic_energy_cube"}}, new String[][] {{"input", "smelter", "items"}, {"battery", "smelter", "energy"}});
        var plan = SemanticMachineLayout.compile(design, 12, REGISTRY);
        requireBuildable(plan);
        verifyRoutes(plan);
        check(plan.report().getAsJsonArray("connections").size() == 2, "both physical networks exist");
        Map<String, JsonObject> cells = cells(plan);
        for (JsonElement clearance : plan.report().getAsJsonArray("clearance_cells")) {
            check(!cells.containsKey(clearance.toString()), "maintenance body/head cell is never occupied");
        }
        check(cells.values().stream().anyMatch(c -> id(c).equals("mekanism:basic_logistical_transporter")), "items use real transporter blocks");
        check(cells.values().stream().anyMatch(c -> id(c).equals("mekanism:basic_universal_cable")), "energy uses real cable blocks");
        Set<String> modes = new HashSet<>();
        for (JsonElement e : plan.report().getAsJsonArray("configurations")) {
            JsonObject config = e.getAsJsonObject(); modes.add(config.get("mode").getAsString());
            check(cells.containsKey(config.get("offset").toString()), "each native configuration binds a planned physical device");
        }
        check(modes.containsAll(Set.of("input", "output", "pull")), "compiled native operations configure both endpoints and real transporter extraction");
    }

    private static void compilesMixedResourceRoutes() {
        var plan = SemanticMachineLayout.compile(graph(new String[][] {{"water", "create:fluid_tank"},
                {"separator", "mekanism:electrolytic_separator"}, {"gas", "mekanism:basic_chemical_tank"},
                {"power", "mekanism:basic_energy_cube"}}, new String[][] {{"water", "separator", "fluids"},
                {"separator", "gas", "chemicals"}, {"power", "separator", "energy"}}), 16, REGISTRY);
        requireBuildable(plan); verifyRoutes(plan);
        Set<String> transports = new HashSet<>();
        plan.report().getAsJsonArray("connections").forEach(e -> transports.add(e.getAsJsonObject().get("transport_id").getAsString()));
        check(transports.containsAll(Set.of("mekanism:basic_mechanical_pipe", "mekanism:basic_pressurized_tube", "mekanism:basic_universal_cable")), "three real media use distinct supported native conduits");
        check(plan.report().getAsJsonArray("obligations").toString().contains("source output/ejection"), "exact side modes remain an explicit native configuration obligation");
    }

    private static void compilesActualKineticTurns() {
        JsonObject graph = graph(new String[][] {{"drive", "create:shaft"}, {"buffer", "minecraft:chest"},
                {"spare", "minecraft:barrel"}, {"mill", "create:millstone"}}, new String[][] {{"drive", "mill", "kinetic"}});
        var plan = SemanticMachineLayout.compile(graph, 16, REGISTRY);
        requireBuildable(plan); verifyRoutes(plan);
        JsonArray path = plan.report().getAsJsonArray("connections").get(0).getAsJsonObject().getAsJsonArray("route");
        Map<String, JsonObject> cells = cells(plan);
        boolean alongX = false, alongZ = false;
        for (int i = 0; i < path.size(); i++) {
            JsonObject state = cells.get(path.get(i).toString()).getAsJsonObject("properties");
            check(state.get("axis").getAsString().equals("y"), "chain drive shaft is vertical");
            boolean axis = state.get("axis_along_first").getAsBoolean(); alongX |= axis; alongZ |= !axis;
            if (i == 0) continue;
            JsonArray a = path.get(i - 1).getAsJsonArray(), b = path.get(i).getAsJsonArray();
            if (a.get(1).getAsInt() == b.get(1).getAsInt()) {
                boolean previous = cells.get(a.toString()).getAsJsonObject("properties").get("axis_along_first").getAsBoolean();
                check(axis == previous, "horizontal neighbor drives agree on chain axis");
                check(axis == (a.get(0).getAsInt() != b.get(0).getAsInt()), "horizontal propagation follows the real chain connection axis");
            }
        }
        check(alongX && alongZ, "turning a diagonal kinetic route uses both horizontal connection axes");
    }

    private static void emitsActualAeCenterParts() {
        var plan = SemanticMachineLayout.compile(graph(new String[][] {{"network", "ae2:energy_acceptor"}, {"disk", "ae2:drive"}},
                new String[][] {{"network", "disk", "ae_network"}}), 8, REGISTRY);
        requireBuildable(plan); verifyRoutes(plan);
        long parts = cells(plan).values().stream().filter(c -> c.has("part")).peek(c -> {
            check(!c.has("block_id"), "a cable part is not represented as a fake full block");
            check(c.get("item_id").getAsString().equals("ae2:fluix_covered_dense_cable"), "native AE backbone part item is retained");
        }).count();
        check(parts > 0, "AE network contains actual center cable parts");
        check(cells(plan).values().stream().filter(c -> id(c).equals("ae2:drive")).allMatch(c ->
                c.getAsJsonObject("properties").get("facing").getAsString().equals("north")), "drive front is excluded from cable ports by explicit orientation");
    }

    private static void failsClosedForUnprovenInterfacesAndMissingDependencies() {
        JsonObject invalid = graph(new String[][] {{"a", "create:shaft"}, {"b", "mekanism:energized_smelter"}},
                new String[][] {{"a", "b", "energy"}});
        var rejected = SemanticMachineLayout.compile(invalid, 8, REGISTRY);
        check(!rejected.buildable() && rejected.blueprint().getAsJsonArray("blocks").isEmpty(), "rotation is not silently converted into FE and partial equipment is not executable");
        JsonObject valid = graph(new String[][] {{"a", "minecraft:chest"}, {"b", "create:depot"}}, new String[][] {{"a", "b", "items"}});
        var absent = new SemanticMachineLayout.Registry() {
            public boolean blockExists(String id) { return !id.equals("mekanism:basic_logistical_transporter"); }
            public boolean itemExists(String id) { return true; }
            public boolean supportsState(String id, Map<String, String> properties) { return true; }
        };
        check(!SemanticMachineLayout.compile(valid, 8, absent).buildable(), "missing optional transport dependency fails before construction");
        JsonObject unknown = graph(new String[][] {{"reactor", "mekanism:fission_reactor_casing"}}, new String[][] {});
        check(!SemanticMachineLayout.compile(unknown, 8, REGISTRY).buildable(), "multiblock casing is not mistaken for a one-block working reactor");
    }

    private static void constrainsDimensionsAndNeverClaimsProduction() {
        JsonObject design = graph(new String[][] {{"a", "minecraft:chest"}, {"b", "minecraft:barrel"}}, new String[][] {{"a", "b", "items"}});
        var accepted = SemanticMachineLayout.compile(design, 8, REGISTRY);
        requireBuildable(accepted);
        for (String key : Set.of("construction_complete", "configuration_complete", "production_verified")) check(!accepted.report().get(key).getAsBoolean(), key + " needs real observations");
        design.add("constraints", JsonParser.parseString("{\"max_width\":2,\"max_depth\":2,\"max_height\":2}").getAsJsonObject());
        check(!SemanticMachineLayout.compile(design, 8, REGISTRY).buildable(), "requested maintenance space is not silently expanded beyond dimensional constraints");
    }

    private static void deterministicAndBoundedLargeLayout() {
        JsonObject design = graph(new String[][] {{"storage", "minecraft:barrel"}}, new String[][] {});
        design.getAsJsonArray("components").get(0).getAsJsonObject().addProperty("count", 600);
        var first = SemanticMachineLayout.compile(design, 128, REGISTRY);
        var second = SemanticMachineLayout.compile(design, 128, REGISTRY);
        requireBuildable(first);
        check(first.blueprint().toString().equals(second.blueprint().toString()), "the same semantic graph has stable internal placements");
        check(first.blueprint().getAsJsonArray("blocks").size() == 600, "expanded repeated equipment is not truncated at the former 512-block limit");
        check(cells(first).keySet().stream().anyMatch(k -> Math.abs(JsonParser.parseString(k).getAsJsonArray().get(0).getAsInt()) > 8), "layout is not constrained by the former eight-block survey radius");
    }

    private static void verifyRoutes(SemanticMachineLayout.Result plan) {
        Map<String, JsonObject> cells = cells(plan);
        Map<String, JsonArray> anchors = new HashMap<>();
        plan.report().getAsJsonArray("components").forEach(e -> anchors.put(e.getAsJsonObject().get("name").getAsString(), e.getAsJsonObject().getAsJsonArray("offset")));
        for (JsonElement element : plan.report().getAsJsonArray("connections")) {
            JsonObject connection = element.getAsJsonObject(); JsonArray path = connection.getAsJsonArray("route");
            JsonArray previous = anchors.get(connection.get("from").getAsString());
            for (JsonElement step : path) {
                check(cells.containsKey(step.toString()), "every reported transport step has an executable internal target");
                check(distance(previous, step.getAsJsonArray()) == 1, "each transport step is face-adjacent to its predecessor");
                previous = step.getAsJsonArray();
            }
            check(distance(previous, anchors.get(connection.get("to").getAsString())) == 1, "transport route physically touches the receiving device");
        }
    }
    private static void interruptedCompilationDoesNotProduceAPlan() {
        Thread.currentThread().interrupt();
        try {
            SemanticMachineLayout.compile(graph(new String[][] {{"storage", "minecraft:barrel"}}, new String[][] {}), 8, REGISTRY);
            throw new AssertionError("interrupted compiler returned a construction plan");
        } catch (java.util.concurrent.CancellationException expected) {
            check(Thread.currentThread().isInterrupted(), "cancellation preserves the caller's interruption state");
        } finally { Thread.interrupted(); }
    }
    private static int distance(JsonArray a, JsonArray b) { int n = 0; for (int i = 0; i < 3; i++) n += Math.abs(a.get(i).getAsInt() - b.get(i).getAsInt()); return n; }
    private static Map<String, JsonObject> cells(SemanticMachineLayout.Result plan) {
        Map<String, JsonObject> result = new HashMap<>();
        plan.blueprint().getAsJsonArray("blocks").forEach(e -> { JsonObject c = e.getAsJsonObject(); check(result.put(c.get("offset").toString(), c) == null, "physical targets never overlap"); });
        return result;
    }
    private static String id(JsonObject cell) { return cell.get(cell.has("block_id") ? "block_id" : "item_id").getAsString(); }
    private static JsonObject graph(String[][] nodes, String[][] edges) {
        JsonObject design = new JsonObject(); JsonArray components = new JsonArray(), connections = new JsonArray();
        for (String[] n : nodes) { JsonObject c = new JsonObject(); c.addProperty("name", n[0]); c.addProperty("block_id", n[1]); c.addProperty("count", 1); c.addProperty("role", "process equipment"); components.add(c); }
        for (String[] e : edges) { JsonObject c = new JsonObject(); c.addProperty("from", e[0]); c.addProperty("to", e[1]); c.addProperty("medium", e[2]); c.addProperty("purpose", "supply the declared process resource"); connections.add(c); }
        design.add("components", components); design.add("connections", connections); return design;
    }
    private static void requireBuildable(SemanticMachineLayout.Result result) { check(result.buildable(), "expected compiled layout: " + result.report().get("unsupported")); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
