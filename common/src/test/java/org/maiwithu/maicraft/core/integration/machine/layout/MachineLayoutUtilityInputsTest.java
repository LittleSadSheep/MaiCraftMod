// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.layout;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.Map;
import org.maiwithu.maicraft.core.integration.machine.MachineDesignReview;
import org.maiwithu.maicraft.core.integration.machine.utility.MachineUtilityInputs;

/** Exercises passive source materialization, shared physical distribution and fail-closed layout limits. */
public final class MachineLayoutUtilityInputsTest {
    private static int checks;
    private static final SemanticMachineLayout.Registry REGISTRY = new SemanticMachineLayout.Registry() {
        public boolean blockExists(String id) { return true; }
        public boolean itemExists(String id) { return true; }
        public boolean supportsState(String id, Map<String, String> state) { return true; }
    };
    public static void main(String[] args) {
        oneKineticInputFeedsTwoConsumers();
        horizontalInputUsesAnActualGearbox();
        separateMediaHaveDistinctRealInputs();
        rejectsInvalidAbstractInputsAndMissingAdapters();
        System.out.println("MachineLayoutUtilityInputsTest: " + checks + " checks passed");
    }
    private static void oneKineticInputFeedsTwoConsumers() {
        JsonObject design = design("create:millstone", 2, "kinetic");
        var plan = SemanticMachineLayout.compile(design, 16, REGISTRY); buildable(plan);
        check(MachineUtilityInputs.parse(plan.blueprint()).size() == 1, "one physical boundary serves an expanded machine group");
        check(plan.report().getAsJsonArray("connections").size() == 2, "both consumers receive physical internal routes");
        Map<String, JsonObject> cells = cells(plan);
        check(cells.values().stream().filter(c -> c.get("block_id").getAsString().equals("create:shaft")).count() == 1, "one real passive shaft, no extra source per consumer");
        check(cells.values().stream().noneMatch(c -> c.get("block_id").getAsString().contains("creative")), "no creative generator materialized");
        Map<String, Integer> usage = new HashMap<>();
        plan.report().getAsJsonArray("connections").forEach(e -> {
            JsonObject route = e.getAsJsonObject(); JsonArray previous = route.getAsJsonArray("source_offset");
            for (var step : route.getAsJsonArray("route")) {
                check(cells.containsKey(step.toString()), "every internal route cell is a real target");
                check(distance(previous, step.getAsJsonArray()) == 1, "internal route is physically contiguous");
                usage.merge(step.toString(), 1, Integer::sum); previous = step.getAsJsonArray();
            }
            check(distance(previous, route.getAsJsonArray("destination_offset")) == 1, "route reaches the declared consumer");
        });
        check(usage.values().stream().anyMatch(n -> n > 1), "one entry shares an internal shaft network rather than demanding duplicate external inputs");
        check(plan.report().getAsJsonArray("construction_stages").toString().contains("await_external_utility_hookup"), "construction leaves external hookup as a distinct phase");
        check(!plan.report().get("utility_connection_verified").getAsBoolean(), "physical layout never claims native supply");
        check(SemanticMachineLayout.compile(design, 16, REGISTRY).blueprint().equals(plan.blueprint()), "same design produces stable boundary coordinates");
    }
    private static void horizontalInputUsesAnActualGearbox() {
        JsonObject design = design("create:millstone", 1, "kinetic"); input(design).addProperty("face", "west");
        var plan = SemanticMachineLayout.compile(design, 12, REGISTRY); buildable(plan);
        var port = MachineUtilityInputs.parse(plan.blueprint()).getFirst();
        check(port.face().getName().equals("west"), "requested exterior connection face retained");
        Map<String, JsonObject> cells = cells(plan);
        check(cells.values().stream().filter(c -> c.get("block_id").getAsString().equals("create:gearbox")).count() == 1, "horizontal shaft turns through one physical gearbox");
        check(cells.get(port.json().get("offset").toString()).getAsJsonObject("properties").get("axis").getAsString().equals("x"), "shaft axis agrees with west hookup face");
    }
    private static void separateMediaHaveDistinctRealInputs() {
        JsonObject design = design("mekanism:electrolytic_separator", 1, "energy");
        JsonObject water = input(design).deepCopy(); water.addProperty("id", "water"); water.addProperty("medium", "fluids"); water.addProperty("resource", "minecraft:water");
        design.getAsJsonArray("external_inputs").add(water);
        var plan = SemanticMachineLayout.compile(design, 16, REGISTRY); buildable(plan);
        var inputs = MachineUtilityInputs.parse(plan.blueprint());
        check(inputs.size() == 2 && inputs.get(0).blockId().equals("mekanism:basic_universal_cable")
                && inputs.get(1).blockId().equals("mekanism:basic_mechanical_pipe"), "power and fluid use distinct native passive connectors");
        check(inputs.get(1).resource().equals("minecraft:water"), "fluid identity survives physical expansion");
        check(!plan.blueprint().toString().contains("generator"), "no unsolicited power plant introduced");
    }
    private static void rejectsInvalidAbstractInputsAndMissingAdapters() {
        JsonObject unknown = design("create:millstone", 1, "kinetic"); input(unknown).add("consumers", JsonParser.parseString("[\"missing\"]"));
        check(!valid(unknown), "unknown consumer rejected before layout");
        JsonObject many = design("create:millstone", 1, "kinetic"); JsonObject second = input(many).deepCopy(); second.addProperty("id", "other"); many.getAsJsonArray("external_inputs").add(second);
        check(!valid(many), "multiple inputs for one medium require separate-network reasons");
        JsonObject unsupported = design("minecraft:barrel", 1, "kinetic");
        check(!SemanticMachineLayout.compile(unsupported, 12, REGISTRY).buildable(), "logical declaration cannot invent a barrel rotation input");
        var absent = new SemanticMachineLayout.Registry() {
            public boolean blockExists(String id) { return !id.equals("create:shaft"); }
            public boolean itemExists(String id) { return true; }
            public boolean supportsState(String id, Map<String, String> state) { return true; }
        };
        check(!SemanticMachineLayout.compile(design("create:millstone", 1, "kinetic"), 12, absent).buildable(), "missing passive connector dependency fails before construction");
        JsonObject onsite = design("create:millstone", 1, "kinetic"); onsite.addProperty("supply_preference", "onsite"); check(!valid(onsite), "onsite source choice requires a reason");
        onsite.addProperty("onsite_reason", "Remote installation needs its own small windmill."); check(valid(onsite), "explicit local utility choice accepted without automatic generation");
    }
    private static JsonObject design(String block, int count, String medium) {
        JsonObject design = JsonParser.parseString("""
                {"components":[{"name":"machines","block_id":"create:millstone","count":1,"role":"process consumers"}],
                 "connections":[],"external_inputs":[{"id":"city","medium":"kinetic","consumers":["machines"]}]}
                """).getAsJsonObject();
        JsonObject component = design.getAsJsonArray("components").get(0).getAsJsonObject(); component.addProperty("block_id", block); component.addProperty("count", count);
        input(design).addProperty("medium", medium); return design;
    }
    private static JsonObject input(JsonObject design) { return design.getAsJsonArray("external_inputs").get(0).getAsJsonObject(); }
    private static boolean valid(JsonObject design) { return MachineDesignReview.review(design, REGISTRY::blockExists, REGISTRY::itemExists).getAsJsonObject("validation").get("valid").getAsBoolean(); }
    private static Map<String, JsonObject> cells(SemanticMachineLayout.Result plan) {
        Map<String, JsonObject> result = new HashMap<>(); plan.blueprint().getAsJsonArray("blocks").forEach(e -> {
            JsonObject cell = e.getAsJsonObject(); check(result.put(cell.get("offset").toString(), cell) == null, "shared network installs each physical cell once");
        }); return result;
    }
    private static int distance(JsonArray a, JsonArray b) { int n = 0; for (int i = 0; i < 3; i++) n += Math.abs(a.get(i).getAsInt() - b.get(i).getAsInt()); return n; }
    private static void buildable(SemanticMachineLayout.Result plan) { check(plan.buildable(), "expected passive input layout: " + plan.report()); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
