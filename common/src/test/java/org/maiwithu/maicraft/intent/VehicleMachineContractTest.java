package org.maiwithu.maicraft.intent;

import com.google.gson.JsonParser;
import java.util.Set;

public final class VehicleMachineContractTest {
    public static void main(String[] args) {
        accepts("""
                {"ability":"maicraft:inspect_machine","outcome":"analyze the observed structure",
                 "parameters":{"structure_id":"00000000-0000-0000-0000-000000000001"}}
                """);
        accepts("""
                {"ability":"maicraft:operate_machine","outcome":"drive to the dock",
                 "target":{"kind":"coordinates","position":{"x":10,"y":64,"z":20}},
                 "parameters":{"operation":"drive_vehicle","structure_id":"00000000-0000-0000-0000-000000000001","allow_use":true}}
                """);
        rejects("""
                {"ability":"maicraft:operate_machine","outcome":"drive somewhere",
                 "parameters":{"operation":"drive_vehicle","structure_id":"00000000-0000-0000-0000-000000000001","allow_use":true}}
                """);
        rejects("""
                {"ability":"maicraft:inspect_machine","outcome":"inspect",
                 "target":{"kind":"current_place"},"parameters":{"structure_id":"00000000-0000-0000-0000-000000000001"}}
                """);
        rejects("""
                {"ability":"maicraft:inspect_machine","outcome":"inspect","parameters":{"structure_id":"invalid"}}
                """);
        rejects("""
                {"ability":"maicraft:inspect_machine","outcome":"inspect",
                 "parameters":{"structure_id":"00000000-0000-0000-0000-000000000001","radius":4}}
                """);
        System.out.println("VehicleMachineContractTest: passed");
    }
    private static void accepts(String source) {
        Goal goal=Goal.fromJson(JsonParser.parseString(source).getAsJsonObject());
        SemanticGoalContract.validate(goal,Set.of(goal.ability()));
    }
    private static void rejects(String source) {
        try { accepts(source); throw new AssertionError("ambiguous vehicle contract accepted"); }
        catch(SemanticContractException expected) { }
    }
}
