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
        System.out.println("TravelTransportContractTest: passed");
    }

    private static Goal goal(JsonObject parameters) {
        return new Goal("maicraft:travel", "Reach the destination", null, parameters.toString(), "{}", List.of(), List.of());
    }
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
