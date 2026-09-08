package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.util.List;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.tools.work.MoveToTool;
import org.maiwithu.maicraft.core.tools.work.SemanticExploreApi;
import org.maiwithu.maicraft.core.tools.work.SemanticExploreTool;

/** Semantic mode requests survive adaptation; an unknown destination cannot pretend to be a forced transport trip. */
public final class TravelTransportContractTest {
    public static void main(String[] args) {
        for (String mode : List.of("auto", "ground", "jetpack", "elevator")) {
            JsonObject parameters = new JsonObject();
            parameters.addProperty("block_id", "minecraft:crafting_table");
            parameters.addProperty("transport_mode", mode);
            var action = (IntentAction.Tool) AbilityAdapter.adapt(goal(parameters), null, null);
            check(action.toolName().equals("goto") && action.arguments().get("transport_mode").getAsString().equals(mode),
                    "travel must forward every declared mode to its movement record");
            parameters.remove("block_id");
            parameters.addProperty("semantic_target", "coast");
            IntentAction discovery = AbilityAdapter.adapt(goal(parameters), null, null);
            if (mode.equals("auto") || mode.equals("ground")) {
                var explore = (IntentAction.Tool) discovery;
                check(explore.toolName().equals("explore") && explore.arguments().get("transport_mode").getAsString().equals(mode),
                        "semantic exploration cannot silently discard ground-only authority");
                var record = SemanticExploreApi.newRecord(new ToolContext("explore-test", 0),
                        "coast", 128, false, explore.arguments().get("transport_mode").getAsString());
                check(record.transportMode == TransportMode.parse(mode) && !record.mayAlterTerrain,
                        "exploration record retains mode without widening terrain permission");
            } else {
                check(discovery instanceof IntentAction.Decision decision
                                && decision.snapshot().question().contains("located destination"),
                        "forced jetpack/elevator exploration must explain the missing endpoint");
                try {
                    SemanticExploreApi.newRecord(new ToolContext("forced-explore", 0), "coast", 128, false, mode);
                    throw new AssertionError("direct exploration API must also reject forced transport to an unknown destination");
                } catch (IllegalArgumentException expected) {
                    check(expected.getMessage().contains("located destination"), "direct exploration gives the same teaching error");
                }
            }
        }
        check(SemanticExploreApi.newRecord(new ToolContext("legacy", 0), "coast", 128, false).transportMode == TransportMode.AUTO,
                "legacy exploration calls retain the default policy");
        JsonObject invalid = new JsonObject();
        invalid.addProperty("transport_mode", "teleport");
        invalid.addProperty("semantic_target", "coast");
        try {
            AbilityAdapter.adapt(goal(invalid), null, null);
            throw new AssertionError("semantic adaptation must reject an invalid mode before choosing exploration");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("transport_mode"), "invalid modes identify the input to correct");
        }
        check(new MoveToTool().parameterSchema().toString().contains("transport_mode")
                        && new SemanticExploreTool().parameterSchema().toString().contains("transport_mode")
                        && SemanticAbilityCatalog.describe("maicraft:travel").toString().contains("transport_mode"),
                "public and internal contracts all expose the same travel preference");
        destinationPrecision();
        regionalDiscovery();
        System.out.println("TravelTransportContractTest: passed");
    }

    private static void regionalDiscovery() {
        for(String mode:List.of("auto","ground","jetpack")) {
            JsonObject parameters=new JsonObject();
            parameters.addProperty("semantic_target","lower_platform"); parameters.addProperty("transport_mode",mode);
            var action=(IntentAction.Tool)AbilityAdapter.adapt(goal(parameters),null,null);
            check(action.toolName().equals("travel_region") && action.arguments().get("direction").getAsString().equals("down"),
                    "a lower platform can be discovered before its coordinates are known");
            check(!action.arguments().has("x") && action.arguments().get("transport_mode").getAsString().equals(mode),
                    "discovery preserves movement choice without fabricating a destination");
            parameters.addProperty("direction","up");
            try { AbilityAdapter.adapt(goal(parameters),null,null); throw new AssertionError("contradictory direction accepted"); }
            catch(IllegalArgumentException expected) { }
            parameters.remove("direction"); parameters.addProperty("exact",true);
            try { AbilityAdapter.adapt(goal(parameters),null,null); throw new AssertionError("unknown exact cell accepted"); }
            catch(IllegalArgumentException expected) { }
        }
    }

    private static void destinationPrecision() {
        Goal known = new Goal("maicraft:travel", "Reach the upper floor",
                new Goal.SemanticTarget("coordinates", null, new Goal.WorldPosition(120, 115, -40, null), null),
                "{}", "{}", List.of(), List.of());
        JsonObject near = ((IntentAction.Tool) AbilityAdapter.adapt(known, null, null)).arguments();
        check(near.get("y").getAsInt() == 115 && !near.get("exact").getAsBoolean(),
                "ordinary travel keeps a known floor height without turning it into one exact cell");
        var movement = new org.maiwithu.maicraft.core.tools.MovementOps();
        var defaults = (org.maiwithu.maicraft.core.task.move.MoveToTaskRecord) movement.moveTo(
                120D, 115D, -40D, null, false, false, "auto", false,
                null, null, null, new ToolContext("approximate", 0));
        check(!defaults.exact && defaults.horizontalRadius == 3 && defaults.verticalTolerance == 2 && defaults.y == 115D,
                "the public movement boundary defaults to a three-block radius and two-block height tolerance");
        JsonObject precise = known.parameters();
        precise.addProperty("exact", true);
        JsonObject exact = ((IntentAction.Tool) AbilityAdapter.adapt(known.withParameters(precise), null, null)).arguments();
        check(exact.get("y").getAsInt() == 115 && exact.get("exact").getAsBoolean(), "explicit exact standing survives adaptation");

        JsonObject destination = new JsonObject();
        destination.addProperty("x", 120.5);
        destination.addProperty("z", -40.5);
        JsonObject parameters = new JsonObject();
        parameters.add("destination", destination);
        parameters.addProperty("horizontal_radius", 20);
        parameters.addProperty("vertical_tolerance", 8);
        Goal horizontal = Goal.fromJson(goal(parameters).toJson());
        SemanticGoalContract.validate(horizontal, java.util.Set.of("maicraft:travel"));
        JsonObject column = ((IntentAction.Tool) AbilityAdapter.adapt(horizontal, null, null)).arguments();
        check(!column.has("y") && column.get("x").getAsDouble() == 120.5
                        && column.get("horizontal_radius").getAsInt() == 20 && column.get("vertical_tolerance").getAsInt() == 8,
                "unknown height stays absent through JSON round-trip; broad valid tolerances are preserved");
        destination.addProperty("y", 115.5);
        JsonObject hinted = ((IntentAction.Tool) AbilityAdapter.adapt(goal(parameters), null, null)).arguments();
        check(hinted.get("y").getAsDouble() == 115.5, "travel-specific coordinates retain fractional height hints");
        destination.remove("y");
        parameters.addProperty("exact", true);
        reject(parameters, "exact travel cannot invent an unknown height");
        parameters.remove("exact");
        parameters.addProperty("horizontal_radius", -1);
        reject(parameters, "negative tolerance cannot silently become another goal");
        parameters.addProperty("horizontal_radius", "3");
        reject(parameters, "numeric strings are not coordinate tolerances");
        parameters.remove("horizontal_radius");
        destination.addProperty("ignored_waypoint", 7);
        reject(parameters, "unknown destination fields must not be discarded");

        JsonObject biome = new JsonObject();
        biome.addProperty("biome_id", "minecraft:plains");
        var discovery = (IntentAction.Tool) AbilityAdapter.adapt(goal(biome), null, null);
        check(discovery.toolName().equals("explore") && !discovery.arguments().has("x")
                        && !discovery.arguments().has("y") && !discovery.arguments().has("z"),
                "biome discovery keeps its region predicate and never fabricates a precise destination");
        biome.addProperty("exact", true);
        check(AbilityAdapter.adapt(goal(biome), null, null) instanceof IntentAction.Decision,
                "exact=true cannot silently weaken to an approximate biome search");
        try {
            movement.moveTo(120D, null, -40D, null, false, false, "auto", false,
                    true, null, null, new ToolContext("missing-height", 0));
            throw new AssertionError("the raw public movement API must also reject exact standing without Y");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("exact=true"), "missing exact coordinates give a corrective error");
        }
    }

    private static void reject(JsonObject parameters, String reason) {
        try { SemanticGoalContract.validate(goal(parameters), java.util.Set.of("maicraft:travel")); }
        catch (SemanticContractException expected) { return; }
        throw new AssertionError(reason);
    }

    private static Goal goal(JsonObject parameters) {
        return new Goal("maicraft:travel", "Reach the destination", null, parameters.toString(), "{}", List.of(), List.of());
    }
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
