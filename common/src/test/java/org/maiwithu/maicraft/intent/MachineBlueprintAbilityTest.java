// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;

/** Models may declare structure, while ambiguous sources and cross-operation fields remain errors. */
public final class MachineBlueprintAbilityTest {
    private static final String URI = "maicraft://knowledge/ponder/structure/create-deployer/0123456789abcdef";

    public static void main(String[] args) {
        for (String ability : Set.of(MachineAbilityAdapter.DESIGN, MachineAbilityAdapter.BUILD, MachineAbilityAdapter.MODIFY)) {
            for (int sources = 0; sources < 8; sources++) {
                JsonObject parameters = parameters(ability);
                if ((sources & 1) != 0) parameters.add("design", json("""
                        {"components":[{"name":"buffer","block_id":"minecraft:chest","count":1,"role":"storage"}],"connections":[]}
                        """));
                if ((sources & 2) != 0) parameters.add("blueprint", blueprint());
                if ((sources & 4) != 0) parameters.addProperty("blueprint_uri", URI);
                boolean valid = Integer.bitCount(sources) == 1
                        && (!MachineAbilityAdapter.MODIFY.equals(ability) || (sources & 1) == 0);
                check(ability, parameters, valid);
            }
            JsonObject parameters = parameters(ability);
            parameters.add("blueprint", blueprint());
            parameters.addProperty("allow_use", true);
            check(ability, parameters, false);
            parameters.remove("allow_use");
            parameters.getAsJsonObject("blueprint").getAsJsonArray("blocks").get(0).getAsJsonObject()
                    .add("click_script", json("{\"button\":\"right\"}"));
            check(ability, parameters, false);
        }

        JsonObject modification = parameters(MachineAbilityAdapter.MODIFY);
        modification.add("blueprint", blueprint());
        modification.addProperty("replace_existing", true);
        modification.addProperty("material_policy", "inventory_only");
        check(MachineAbilityAdapter.MODIFY, modification, true);
        modification.addProperty("replace_block_entities", true);
        check(MachineAbilityAdapter.MODIFY, modification, true);
        modification.addProperty("replace_existing", false);
        check(MachineAbilityAdapter.MODIFY, modification, false);
        modification.addProperty("replace_existing", true);
        modification.addProperty("source_label", "drive");
        check(MachineAbilityAdapter.MODIFY, modification, false);
        modification = parameters(MachineAbilityAdapter.MODIFY);
        modification.addProperty("operation", "connect_mechanical_power");
        modification.addProperty("source_label", "drive");
        check(MachineAbilityAdapter.MODIFY, modification, true);
        modification.add("blueprint", blueprint());
        check(MachineAbilityAdapter.MODIFY, modification, false);

        JsonObject build = parameters(MachineAbilityAdapter.BUILD);
        build.add("blueprint", blueprint());
        build.addProperty("replace_block_entities", true);
        check(MachineAbilityAdapter.BUILD, build, false);
        build.addProperty("replace_existing", true);
        check(MachineAbilityAdapter.BUILD, build, true);
        build.addProperty("replace_block_entities", "true");
        check(MachineAbilityAdapter.BUILD, build, false);

        JsonObject uri = parameters(MachineAbilityAdapter.BUILD);
        for (String invalid : Set.of("https://example.com/layout.json", "file:///tmp/layout.json",
                "maicraft://knowledge/ponder/structure/", "maicraft://knowledge/ponder/scene/example")) {
            uri.addProperty("blueprint_uri", invalid);
            check(MachineAbilityAdapter.BUILD, uri, false);
        }
        JsonObject operation = json("{\"operation\":\"set_control\",\"snapshot_id\":\"receipt\",\"powered\":true}");
        operation.add("blueprint", blueprint());
        check(MachineAbilityAdapter.OPERATE, operation, false);
        JsonObject review = new JsonObject();
        review.add("blueprint", blueprint());
        SemanticGoalContract.validate(goal(MachineAbilityAdapter.DESIGN, review, false), Set.of(MachineAbilityAdapter.DESIGN));
        System.out.println("MachineBlueprintAbilityTest: passed");
    }

    private static JsonObject blueprint() {
        return json("""
                {"schema_version":1,"blocks":[
                  {"offset":[0,0,0],"block_id":"minecraft:stone"},
                  {"offset":[1,0,0],"block_id":"minecraft:air"}],
                  "metadata":{"source":"tutorial"},"evidence":{"observed_nbt":{"Speed":256}}}
                """);
    }

    private static JsonObject parameters(String ability) {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("snapshot_id", "receipt");
        if (MachineAbilityAdapter.MODIFY.equals(ability)) parameters.addProperty("operation", "apply_blueprint");
        return parameters;
    }

    private static void check(String ability, JsonObject parameters, boolean expected) {
        try {
            SemanticGoalContract.validate(goal(ability, parameters, true), Set.of(ability));
        } catch (SemanticContractException invalid) {
            if (expected) throw new AssertionError("Valid blueprint contract rejected: " + parameters, invalid);
            return;
        }
        if (!expected) throw new AssertionError("Ambiguous or unsupported contract accepted: " + parameters);
    }

    private static Goal goal(String ability, JsonObject parameters, boolean target) {
        JsonObject request = new JsonObject();
        request.addProperty("ability", ability);
        request.addProperty("outcome", "apply or review the requested machine structure");
        if (target) request.add("target", json("{\"kind\":\"landmark\",\"label\":\"site\"}"));
        request.add("parameters", parameters);
        return Goal.fromJson(request);
    }

    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
}
