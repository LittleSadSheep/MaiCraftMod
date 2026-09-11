// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;

/** Public requests retain construction-only behavior and cannot smuggle slot scripts into production plans. */
public final class MachineProductionContractTest {
    private static final Set<String> ABILITIES = Set.of(MachineAbilityAdapter.BUILD, MachineAbilityAdapter.DESIGN,
            MachineAbilityAdapter.OPERATE, MachineAbilityAdapter.INSPECT);

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        JsonObject build = JsonParser.parseString("""
                {"snapshot_id":"receipt","allow_modify":true,"blueprint":{"schema_version":1,
                 "blocks":[{"offset":[0,0,0],"block_id":"minecraft:stone"}]}}
                """).getAsJsonObject();
        accepts(MachineAbilityAdapter.BUILD, build);
        build.add("production", manifest()); build.addProperty("allow_use", true);
        accepts(MachineAbilityAdapter.BUILD, build);
        JsonObject design = new JsonObject(); design.add("blueprint", build.get("blueprint").deepCopy());
        design.add("production", manifest()); accepts(MachineAbilityAdapter.DESIGN, design);
        Goal reviewed = goal(MachineAbilityAdapter.DESIGN, design);
        JsonObject original = reviewed.toJson();
        var runtime = IntentRuntime.get();
        var compiled = runtime.compile(reviewed, 100);
        if (!original.equals(reviewed.toJson()) || !compiled.goal().toJson().equals(original))
            throw new AssertionError("Typed production data was removed from the actual plan");
        try { runtime.execute(null, reviewed, null, null); throw new AssertionError("Worldless execution started"); }
        catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("not bound")) throw expected;
        }
        JsonObject run = new JsonObject(); run.addProperty("operation", "run_production");
        run.addProperty("snapshot_id", "receipt"); run.addProperty("allow_use", true); run.add("production", manifest());
        accepts(MachineAbilityAdapter.OPERATE, run);
        runtime.compile(new Goal("maicraft:sequence", "review and build production", null, "{}", "{}",
                java.util.List.of(), java.util.List.of(reviewed, goal(MachineAbilityAdapter.BUILD, build))), 100);
        JsonObject missing = run.deepCopy(); missing.remove("production"); rejects(MachineAbilityAdapter.OPERATE, missing);

        JsonObject reversed = build.deepCopy();
        reversed.getAsJsonObject("production").getAsJsonArray("ports").get(0).getAsJsonObject().addProperty("direction", "input");
        rejects(MachineAbilityAdapter.BUILD, reversed);
        JsonObject unsafe = build.deepCopy();
        unsafe.getAsJsonObject("production").getAsJsonArray("configurations").add(JsonParser.parseString("""
                {"id":"click","node":"press","operation":"inventory.transfer","stage":"configure",
                 "arguments":{"action":"deposit","player_slot":0,"slot":1}}
                """));
        rejects(MachineAbilityAdapter.BUILD, unsafe);
        JsonObject unrelatedUse = build.deepCopy(); unrelatedUse.remove("production");
        rejects(MachineAbilityAdapter.BUILD, unrelatedUse);
        JsonObject inspection = new JsonObject(); inspection.addProperty("component_offset", -1);
        rejects(MachineAbilityAdapter.INSPECT, inspection);
        JsonObject review = MachineProductionIntent.review(manifest());
        if (!review.get("valid").getAsBoolean() || review.get("ready").getAsBoolean()
                || review.get("machine_production_verified").getAsBoolean())
            throw new AssertionError("A shape-only review must retain unknown native evidence");
        System.out.println("MachineProductionContractTest: passed");
    }

    private static JsonObject manifest() {
        return JsonParser.parseString("""
                {"schema_version":1,"nodes":[
                  {"id":"feed","kind":"source","offset":[0,0,0]},
                  {"id":"press","kind":"process","offset":[2,2,0],"recipe_id":"create:pressing/iron_ingot","batches":3},
                  {"id":"sink","kind":"sink","offset":[4,0,0]}],
                 "ports":[
                  {"id":"feed-out","node":"feed","offset":[0,0,0],"face":"east","medium":"items","direction":"output"},
                  {"id":"press-in","node":"press","offset":[2,0,0],"face":"west","medium":"items","direction":"input"},
                  {"id":"press-out","node":"press","offset":[2,0,0],"face":"east","medium":"items","direction":"output"},
                  {"id":"sink-in","node":"sink","offset":[4,0,0],"face":"west","medium":"items","direction":"input"}],
                 "links":[
                  {"id":"input","from":"feed-out","to":"press-in","medium":"items","resource":"minecraft:iron_ingot","amount":3,"path":[[0,0,0],[1,0,0],[2,0,0]]},
                  {"id":"output","from":"press-out","to":"sink-in","medium":"items","resource":"create:iron_sheet","amount":3,"path":[[2,0,0],[3,0,0],[4,0,0]]}],
                 "configurations":[],"target":{"node":"sink","medium":"items","resource":"create:iron_sheet"},
                 "observation":{"window_ticks":100,"minimum_output":2,"minimum_events":2,"max_idle_ticks":100}}
                """).getAsJsonObject();
    }

    private static Goal goal(String ability, JsonObject parameters) {
        var target = ability.equals(MachineAbilityAdapter.DESIGN) && !parameters.has("snapshot_id")
                ? null : new Goal.SemanticTarget("landmark", "factory", null, null);
        return new Goal(ability, "Run the declared machine", target,
                parameters.toString(), "{}", java.util.List.of(), java.util.List.of());
    }
    private static void accepts(String ability, JsonObject parameters) {
        Goal goal = goal(ability, parameters);
        SemanticGoalContract.validate(goal, ABILITIES);
        IntentRuntime.get().compile(goal, 100);
    }
    private static void rejects(String ability, JsonObject parameters) {
        try { accepts(ability, parameters); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Accepted invalid production parameters: " + parameters);
    }
}
