package org.maiwithu.maicraft.intent;

import com.google.gson.Gson;
import java.util.List;
import java.util.Map;

/** Public outcomes retain committed source locations while stripping action internals. */
public final class HarvestEvidenceContractTest {
    public static void main(String[] args) throws Exception {
        var sanitize = IntentTask.class.getDeclaredMethod("sanitizeMap", Map.class);
        sanitize.setAccessible(true);
        var position = Map.of("x", -71, "y", 106, "z", -4);
        var harvest = Map.of("position", position, "block_id", "minecraft:spruce_log",
                "block_state", "Block{minecraft:spruce_log}[axis=y]", "natural_tree_filter_enabled", false,
                "slot", 3, "route", List.of(position));
        var child = Map.of("confirmed_harvests", List.of(harvest), "position", position,
                "route", List.of(position), "slot", 3);
        Gson gson = new Gson();
        for (Object encoded : List.of(child, gson.toJsonTree(child), gson.toJson(child))) {
            Object clean = sanitize.invoke(null, Map.of("attempts", List.of(Map.of("child_data", encoded))));
            var data = gson.toJsonTree(clean).getAsJsonObject().getAsJsonArray("attempts")
                    .get(0).getAsJsonObject().getAsJsonObject("child_data");
            var row = data.getAsJsonArray("confirmed_harvests").get(0).getAsJsonObject();
            check(row.getAsJsonObject("position").get("y").getAsInt() == 106,
                    "the exact damaged block must survive every nested result encoding");
            check(row.get("block_state").getAsString().endsWith("[axis=y]"), "preserve the restorable state");
            check(!row.has("slot") && !row.has("route") && !data.has("position")
                    && !data.has("slot") && !data.has("route"), "evidence must not expose internal action plans");
        }
        var bad = Map.of("confirmed_harvests", List.of(Map.of("position", Map.of("x", 0.5, "y", 2, "z", 3))));
        var clean = gson.toJsonTree(sanitize.invoke(null, bad)).getAsJsonObject();
        check(!clean.getAsJsonArray("confirmed_harvests").get(0).getAsJsonObject().has("position"),
                "malformed coordinates must not become an invented block location");
        System.out.println("HarvestEvidenceContractTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
