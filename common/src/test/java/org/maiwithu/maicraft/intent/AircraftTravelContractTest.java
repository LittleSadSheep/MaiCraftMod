package org.maiwithu.maicraft.intent;

import java.util.List;
import java.util.UUID;

/** 远程飞机旅行必须保留真实终点与精度；登船、跨维度和未定位探索不能混入该操作。 */
public final class AircraftTravelContractTest {
    public static void run() {
        String id = UUID.randomUUID().toString();
        String aircraft = "\"transport_mode\":\"aircraft\",\"aircraft_id\":\"" + id + "\"";
        valid("{" + aircraft + ",\"destination\":{\"x\":100,\"z\":200}}");
        valid("{" + aircraft + ",\"destination\":{\"x\":100,\"y\":80,\"z\":200},\"exact\":true}");
        valid("{" + aircraft + ",\"destination\":{\"x\":100,\"z\":200},\"cruise_altitude\":120}");
        for (String invalid : List.of(
                "{" + aircraft + "}",
                "{\"transport_mode\":\"aircraft\",\"destination\":{\"x\":1,\"z\":2}}",
                "{\"aircraft_id\":\"" + id + "\",\"destination\":{\"x\":1,\"z\":2}}",
                "{\"destination\":{\"x\":1,\"z\":2},\"cruise_altitude\":100}",
                "{" + aircraft + ",\"destination\":{\"x\":1,\"z\":2},\"exact\":true}",
                "{" + aircraft + ",\"destination\":{\"x\":1,\"z\":2},\"structure_id\":\"" + id + "\"}",
                "{" + aircraft + ",\"biome_id\":\"minecraft:plains\"}",
                "{" + aircraft + ",\"destination\":{\"x\":1,\"z\":2},\"horizontal_radius\":-1}")) {
            try { valid(invalid); } catch (IllegalArgumentException expected) { continue; }
            throw new AssertionError("非法飞机旅行被接单：" + invalid);
        }
        System.out.println("AircraftTravelContractTest: passed");
    }
    private static void valid(String p) {
        SemanticGoalContract.validate(new Goal("maicraft:travel", "飞往目的地并步行到达", null,
                p, "{}", List.of(), List.of()), IntentRuntime.KNOWN_ABILITIES);
    }
}
