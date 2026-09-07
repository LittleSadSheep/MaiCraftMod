// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone regression suite: run main with Gson; no Minecraft runtime or JUnit required. */
public final class MachineDesignReviewTest {
    private static int checks;
    private static final Set<String> BLOCKS = Set.of("create:millstone", "create:shaft", "ae2:interface", "mekanism:energized_smelter", "minecraft:chest", "minecraft:air");
    private static final Set<String> ITEMS = Set.of("minecraft:iron_ingot");

    private MachineDesignReviewTest() {}

    public static void main(String[] args) {
        reviewsMixedGraphWithoutClaimingFunction();
        rejectsUnknownFieldsAtEveryLevel();
        rejectsInvalidShapesAndCoercions();
        rejectsInvalidReferencesAndRegistryIds();
        enforcesAllBounds();
        reviewsEveryMediumConservatively();
        acceptsExtensibleNamespacedResourceModels();
        reportsDisconnectedAndGroupedComponents();
        handlesRegistryFailuresWithoutTreatingThemAsEvidence();
        acceptsSemanticStyleAndConstraintsButRejectsBlueprints();
        System.out.println("MachineDesignReviewTest: " + checks + " checks passed");
    }

    private static void reviewsMixedGraphWithoutClaimingFunction() {
        JsonObject design = mixedDesign();
        String before = design.toString();
        JsonObject result = review(design);
        check(valid(result), "mixed logical graph passes schema validation");
        check(before.equals(design.toString()), "review does not modify input JSON");
        check(result.get("total_block_count").getAsInt() == 6, "totals all component counts");
        check(result.getAsJsonArray("material_requirements").size() == 4, "aggregates repeated block ids");
        check(result.getAsJsonArray("material_requirements").get(1).getAsJsonObject().get("count").getAsInt() == 3, "sums grouped shaft requirements");
        check(result.getAsJsonObject("graph").get("expected_output").getAsString().equals("minecraft:iron_ingot"), "retains output claim");
        check(result.getAsJsonObject("graph").getAsJsonArray("connections").get(0).getAsJsonObject().get("evidence").getAsString().equals("design_claim_only"), "marks edges as unverified claims");
        JsonObject readiness = result.getAsJsonObject("readiness");
        check(readiness.get("design_validated").getAsBoolean(), "design structural readiness is explicit");
        for (String field : new String[]{"physical_layout_verified", "interfaces_verified", "recipes_verified", "functioning_machine_verified", "executable"}) {
            check(!readiness.get(field).getAsBoolean(), "accepted graph does not assert " + field);
        }
        for (String code : new String[]{"physical_layout", "interface_compatibility", "direction_and_control", "power_budget", "kinetic_stress_and_speed", "ae_network_and_channels", "recipes_and_throughput", "operational_test"}) {
            check(hasObligation(result, code), "mixed graph includes " + code);
        }
        check(result.getAsJsonArray("unsupported_obligations").size() == result.getAsJsonArray("obligations").size(), "review exposes every unverified obligation");
        check(result.getAsJsonArray("integration_hints").size() == 1, "cross-mod item links share a bounded hint");
        check(result.getAsJsonArray("integration_hints").get(0).getAsJsonObject().getAsJsonArray("connection_indices").size() == 2, "hint identifies each mixed-mod edge");
        result.getAsJsonObject("graph").getAsJsonArray("components").get(0).getAsJsonObject().addProperty("role", "changed result");
        check(before.equals(design.toString()), "normalized graph shares no mutable component objects with input");
    }

    private static void rejectsUnknownFieldsAtEveryLevel() {
        JsonObject root = mixedDesign();
        root.addProperty("commands", "break block");
        expectError(root, "unknown_field");
        JsonObject component = mixedDesign();
        component.getAsJsonArray("components").get(0).getAsJsonObject().add("position", JsonParser.parseString("[1,2,3]"));
        expectError(component, "unknown_field");
        JsonObject edge = mixedDesign();
        edge.getAsJsonArray("connections").get(0).getAsJsonObject().addProperty("click_face", "north");
        expectError(edge, "unknown_field");
    }

    private static void rejectsInvalidShapesAndCoercions() {
        expectError(null, "object_required");
        expectError(new JsonObject(), "array_required");
        JsonObject missingCount = mixedDesign();
        missingCount.getAsJsonArray("components").get(0).getAsJsonObject().remove("count");
        expectError(missingCount, "invalid_count");
        for (String raw : new String[]{"\"2\"", "2.5", "2e-1", "0", "-1", "32769", "2147483649", "null", "true", "[]", "{}"}) {
            JsonObject design = mixedDesign();
            design.getAsJsonArray("components").get(0).getAsJsonObject().add("count", JsonParser.parseString(raw));
            expectError(design, "invalid_count");
        }
        for (String raw : new String[]{"2.0", "2e0"}) {
            JsonObject design = singleDesign();
            design.getAsJsonArray("components").get(0).getAsJsonObject().add("count", JsonParser.parseString(raw));
            JsonObject result = review(design);
            check(valid(result) && result.get("total_block_count").getAsInt() == 2, "accepts an exact numeric integer regardless of JSON number spelling");
        }
        JsonObject numericName = mixedDesign();
        numericName.getAsJsonArray("components").get(0).getAsJsonObject().addProperty("name", 123);
        expectError(numericName, "string_required");
        for (String value : new String[]{"", " ", " padded", "control\ncharacter", "x".repeat(65)}) {
            JsonObject design = mixedDesign();
            design.getAsJsonArray("components").get(0).getAsJsonObject().addProperty("name", value);
            expectError(design, "invalid_string");
        }
        JsonObject nullComponent = mixedDesign();
        nullComponent.getAsJsonArray("components").set(0, null);
        expectError(nullComponent, "object_required");
        JsonObject invalidPurpose = mixedDesign();
        invalidPurpose.getAsJsonArray("connections").get(0).getAsJsonObject().remove("purpose");
        expectError(invalidPurpose, "string_required");
        JsonObject nullOutput = mixedDesign();
        nullOutput.add("expected_output", null);
        expectError(nullOutput, "string_required");
    }

    private static void rejectsInvalidReferencesAndRegistryIds() {
        JsonObject duplicate = mixedDesign();
        duplicate.getAsJsonArray("components").add(duplicate.getAsJsonArray("components").get(0).deepCopy());
        expectError(duplicate, "duplicate_component");
        JsonObject duplicateEdge = mixedDesign();
        JsonObject copy = duplicateEdge.getAsJsonArray("connections").get(0).deepCopy().getAsJsonObject();
        copy.addProperty("purpose", "different prose does not create a different edge");
        duplicateEdge.getAsJsonArray("connections").add(copy);
        expectError(duplicateEdge, "duplicate_connection");
        JsonObject dangling = mixedDesign();
        dangling.getAsJsonArray("connections").get(0).getAsJsonObject().addProperty("to", "missing");
        expectError(dangling, "unknown_component");
        JsonObject self = mixedDesign();
        self.getAsJsonArray("connections").get(0).getAsJsonObject().addProperty("to", "drive");
        expectError(self, "self_connection");
        JsonObject unsupported = mixedDesign();
        unsupported.getAsJsonArray("connections").get(0).getAsJsonObject().addProperty("medium", "magic");
        expectError(unsupported, "unsupported_medium");
        for (String id : new String[]{"stone", "Minecraft:stone", "minecraft:", "mine craft:stone"}) {
            JsonObject design = mixedDesign();
            design.getAsJsonArray("components").get(0).getAsJsonObject().addProperty("block_id", id);
            expectError(design, "invalid_identifier");
        }
        JsonObject unknownBlock = mixedDesign();
        unknownBlock.getAsJsonArray("components").get(0).getAsJsonObject().addProperty("block_id", "absent:machine");
        expectError(unknownBlock, "unknown_block");
        JsonObject air = mixedDesign();
        air.getAsJsonArray("components").get(0).getAsJsonObject().addProperty("block_id", "minecraft:air");
        expectError(air, "air_component");
        JsonObject unknownOutput = mixedDesign();
        unknownOutput.addProperty("expected_output", "absent:ingot");
        expectError(unknownOutput, "unknown_item");
    }

    private static void enforcesAllBounds() {
        JsonObject maximumTotal = singleDesign();
        JsonArray components = maximumTotal.getAsJsonArray("components");
        components.get(0).getAsJsonObject().addProperty("count", MachineDesignReview.MAX_COMPONENT_COUNT);
        check(valid(review(maximumTotal)), "accepts the 32768-block physical planning budget");
        components.add(component("overflow", "minecraft:chest", 1));
        expectError(maximumTotal, "total_count_exceeded");
        JsonObject maximumComponents = singleDesign();
        JsonArray list = maximumComponents.getAsJsonArray("components");
        for (int i = 1; i < MachineDesignReview.MAX_COMPONENTS; i++) list.add(component("chest" + i, "minecraft:chest", 1));
        check(valid(review(maximumComponents)), "accepts the 1024 named component budget");
        list.add(component("overflow", "minecraft:chest", 1));
        AtomicInteger registryCalls = new AtomicInteger();
        JsonObject oversized = MachineDesignReview.review(maximumComponents, id -> { registryCalls.incrementAndGet(); return true; }, id -> true);
        check(!valid(oversized) && registryCalls.get() == 0, "oversized component array is rejected before registry traversal");
        JsonObject empty = singleDesign();
        empty.add("components", new JsonArray());
        expectError(empty, "array_size");
        JsonObject maximumEdges = singleDesign();
        for (int i = 1; i < 65; i++) maximumEdges.getAsJsonArray("components").add(component("chest" + i, "minecraft:chest", 1));
        JsonArray edges = maximumEdges.getAsJsonArray("connections");
        for (int i = 0; i < 65 && edges.size() < MachineDesignReview.MAX_CONNECTIONS; i++) {
            for (int j = 0; j < 65 && edges.size() < MachineDesignReview.MAX_CONNECTIONS; j++) {
                if (i != j) edges.add(connection(i == 0 ? "chest" : "chest" + i, j == 0 ? "chest" : "chest" + j, "items"));
            }
        }
        check(valid(review(maximumEdges)), "accepts 4096 unique directed connections");
        edges.add(connection("chest15", "chest16", "fluids"));
        expectError(maximumEdges, "array_size");
        JsonObject largeSite = singleDesign();
        largeSite.add("constraints", JsonParser.parseString("{\"max_width\":257,\"max_depth\":257,\"max_height\":257}"));
        check(valid(review(largeSite)), "accepts a 128-radius site diameter");
        largeSite.getAsJsonObject("constraints").addProperty("max_width", 258);
        expectError(largeSite, "invalid_dimension");
    }

    private static void reviewsEveryMediumConservatively() {
        JsonObject design = singleDesign();
        design.getAsJsonArray("components").add(component("processor", "mekanism:energized_smelter", 1));
        for (String medium : new String[]{"kinetic", "items", "fluids", "energy", "chemicals", "ae_network", "redstone", "heat"}) {
            design.getAsJsonArray("connections").add(connection("chest", "processor", medium));
        }
        JsonObject result = review(design);
        check(valid(result), "structural reviewer accepts all declared media without inventing physical compatibility");
        check(result.getAsJsonArray("integration_hints").size() == 8, "each medium receives a cross-mod compatibility hint");
        check(hasObligation(result, "resource_types_and_side_configuration"), "chemical/fluid links require resource type evidence");
        check(hasObligation(result, "control_and_failure_modes"), "redstone links require control evidence");
        check(hasObligation(result, "heat_transfer_and_temperature"), "heat links require thermal behavior evidence");
        check(!result.getAsJsonObject("readiness").get("interfaces_verified").getAsBoolean(), "nonsensical transport claims are not certified as compatible");
    }

    private static void acceptsExtensibleNamespacedResourceModels() {
        JsonObject design = singleDesign();
        design.getAsJsonArray("components").add(component("processor", "mekanism:energized_smelter", 1));
        JsonArray connections = design.getAsJsonArray("connections");
        connections.add(connection("chest", "processor", "addon:mana"));
        connections.add(connection("chest", "processor", "addon:" + "x".repeat(250)));
        JsonObject result = review(design);
        check(valid(result), "accepts namespaced custom resource models up to 256 characters");
        check(hasObligation(result, "custom_medium_semantics"), "custom resources require explicit definition and runtime evidence");
        check(result.getAsJsonArray("integration_hints").size() == 2, "unknown cross-mod media receive generic compatibility hints without throwing");
        check(!result.getAsJsonObject("readiness").get("interfaces_verified").getAsBoolean(), "custom resource names do not certify endpoint compatibility");
        check(result.getAsJsonObject("graph").getAsJsonArray("connections").get(0).getAsJsonObject().get("medium").getAsString().equals("addon:mana"), "preserves custom resource identity exactly");
        for (String medium : new String[]{"magic", "addon:", "Addon:mana", "addon:ma na"}) {
            connections.set(0, connection("chest", "processor", medium));
            expectError(design, "unsupported_medium");
        }
        connections.set(0, connection("chest", "processor", "addon:" + "x".repeat(251)));
        expectError(design, "invalid_string");
    }

    private static void reportsDisconnectedAndGroupedComponents() {
        JsonObject design = singleDesign();
        design.getAsJsonArray("components").get(0).getAsJsonObject().addProperty("count", 2);
        JsonObject result = review(design);
        check(valid(result), "isolated groups are valid logical hypotheses");
        check(result.getAsJsonArray("warnings").size() == 2, "warns on disconnected component and unspecified instance topology");
        check(!result.getAsJsonObject("graph").has("expected_output"), "output is optional without inventing one");
        check(hasObligation(result, "recipes_and_throughput"), "missing output remains an explicit obligation");
    }

    private static void handlesRegistryFailuresWithoutTreatingThemAsEvidence() {
        JsonObject result = MachineDesignReview.review(singleDesign(), id -> { throw new IllegalStateException("private runtime failure"); }, id -> true);
        check(!valid(result) && hasError(result, "registry_unavailable"), "registry exceptions fail validation explicitly");
        check(!result.toString().contains("private runtime failure"), "does not copy arbitrary registry exception text to LLM");
    }

    private static void acceptsSemanticStyleAndConstraintsButRejectsBlueprints() {
        JsonObject design = singleDesign();
        design.addProperty("style", "compact industrial workshop");
        design.add("constraints", JsonParser.parseString("""
                {"max_width":9,"max_depth":8,"max_height":6,"terrain_fit":"surface",
                 "maintenance_access":true,"preserve_existing":true,"throughput":"one batch at a time"}
                """));
        JsonObject result = review(design);
        check(valid(result), "logical design accepts high-level style and constraints");
        check(result.getAsJsonObject("graph").get("style").getAsString().equals("compact industrial workshop"), "preserves style");
        check(result.getAsJsonObject("graph").getAsJsonObject("constraints").get("max_width").getAsInt() == 9, "preserves bounded semantic constraints");
        check(!result.getAsJsonObject("readiness").get("physical_layout_submitted").getAsBoolean(), "semantic design never claims a physical layout was submitted");

        JsonObject blueprint = singleDesign();
        blueprint.add("blueprint", JsonParser.parseString("""
                {"blocks":[{"offset":[1,0,2],"block_id":"minecraft:chest","properties":{"facing":"north"}}]}
                """));
        expectError(blueprint, "unknown_field");

        JsonObject invalidConstraint = singleDesign();
        invalidConstraint.add("constraints", JsonParser.parseString("{\"max_width\":0}"));
        expectError(invalidConstraint, "invalid_dimension");

        JsonObject lowLevelConstraint = singleDesign();
        lowLevelConstraint.add("constraints", JsonParser.parseString("{\"placements\":[]}"));
        expectError(lowLevelConstraint, "unknown_field");
    }

    private static JsonObject mixedDesign() {
        return JsonParser.parseString("""
                {"components":[
                  {"name":"mill","block_id":"create:millstone","count":1,"role":"processing hypothesis"},
                  {"name":"drive","block_id":"create:shaft","count":2,"role":"rotation transport"},
                  {"name":"spare_drive","block_id":"create:shaft","count":1,"role":"rotation branch"},
                  {"name":"network","block_id":"ae2:interface","count":1,"role":"inventory interface"},
                  {"name":"smelter","block_id":"mekanism:energized_smelter","count":1,"role":"processing hypothesis"}
                ],"connections":[
                  {"from":"drive","to":"mill","medium":"kinetic","purpose":"proposed mechanical input"},
                  {"from":"mill","to":"network","medium":"items","purpose":"proposed collection"},
                  {"from":"network","to":"smelter","medium":"items","purpose":"proposed downstream processing"}
                ],"expected_output":"minecraft:iron_ingot"}
                """).getAsJsonObject();
    }

    private static JsonObject singleDesign() {
        JsonObject design = new JsonObject();
        JsonArray components = new JsonArray();
        components.add(component("chest", "minecraft:chest", 1));
        design.add("components", components);
        design.add("connections", new JsonArray());
        return design;
    }

    private static JsonObject component(String name, String id, int count) {
        JsonObject component = new JsonObject();
        component.addProperty("name", name);
        component.addProperty("block_id", id);
        component.addProperty("count", count);
        component.addProperty("role", "hypothesis");
        return component;
    }

    private static JsonObject connection(String from, String to, String medium) {
        JsonObject connection = new JsonObject();
        connection.addProperty("from", from);
        connection.addProperty("to", to);
        connection.addProperty("medium", medium);
        connection.addProperty("purpose", "hypothesis");
        return connection;
    }

    private static JsonObject review(JsonObject design) { return MachineDesignReview.review(design, BLOCKS::contains, ITEMS::contains); }

    private static boolean valid(JsonObject result) { return result.getAsJsonObject("validation").get("valid").getAsBoolean(); }

    private static boolean hasError(JsonObject result, String code) {
        for (JsonElement error : result.getAsJsonObject("validation").getAsJsonArray("errors")) {
            if (code.equals(error.getAsJsonObject().get("code").getAsString())) return true;
        }
        return false;
    }

    private static boolean hasObligation(JsonObject result, String code) {
        for (JsonElement obligation : result.getAsJsonArray("obligations")) {
            if (code.equals(obligation.getAsJsonObject().get("code").getAsString())) return true;
        }
        return false;
    }

    private static void expectError(JsonObject design, String code) {
        JsonObject result = review(design);
        check(!valid(result) && hasError(result, code), "rejects with " + code);
        check(!result.has("graph") && !result.has("material_requirements"), "invalid input has no misleading partial graph/materials");
        check(!result.getAsJsonObject("readiness").get("executable").getAsBoolean(), "invalid graph remains non-executable");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
