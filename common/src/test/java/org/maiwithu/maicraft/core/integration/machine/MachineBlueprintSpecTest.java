// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Standalone wire-boundary regression tests; no Minecraft or optional mods required. */
public final class MachineBlueprintSpecTest {
    public static void main(String[] args) {
        var mixed = MachineBlueprintSpec.parse(json("""
                {"blocks":[
                  {"offset":[-2,0,1],"block_id":"create:shaft","properties":{"axis":"x"}},
                  {"offset":[-1,0,1],"block_id":"ae2:interface"},
                  {"offset":[0,0,1],"block_id":"mekanism:basic_energy_cube"}]}
                """), 2);
        check(mixed.size() == 3 && mixed.getFirst().properties().get("axis").equals("x"),
                "mixed registered IDs and precise properties must reach registry validation intact");
        reject("{\"blocks\":[{\"offset\":[0,0,0],\"block_id\":\"create:shaft\"},{\"offset\":[0,0,0],\"block_id\":\"minecraft:air\"}]}", 1, "duplicate");
        reject("{\"blocks\":[{\"offset\":[1.5,0,0],\"block_id\":\"create:shaft\"}]}", 2, "integer");
        reject("{\"blocks\":[{\"offset\":[2147483648,0,0],\"block_id\":\"create:shaft\"}]}", 8, "integer");
        reject("{\"blocks\":[{\"offset\":[\"1\",0,0],\"block_id\":\"create:shaft\"}]}", 2, "integer");
        reject("{\"blocks\":[{\"offset\":[-3,0,0],\"block_id\":\"create:shaft\"}]}", 2, "radius");
        reject("{\"blocks\":[{\"offset\":[0,0,0],\"block_id\":\"create:shaft\",\"nbt\":{\"Items\":[]}}]}", 1, "unsupported");
        reject("{\"blocks\":[{\"offset\":[0,0,0],\"block_id\":\"create:shaft\",\"properties\":{\"axis\":true}}]}", 1, "string");
        reject("{\"blocks\":[{\"offset\":[0,0,0],\"block_id\":\"shaft\"}]}", 1, "registry");
        reject("{\"blocks\":[]}", 1, "1..512");
        JsonArray tooMany = new JsonArray();
        for (int index = 0; index <= MachineBlueprintSpec.MAX_BLOCKS; index++) tooMany.add(new JsonObject());
        JsonObject oversized = new JsonObject(); oversized.add("blocks", tooMany);
        reject(oversized.toString(), 8, "1..512");
        try { mixed.clear(); throw new AssertionError("parsed blueprint must be immutable"); }
        catch (UnsupportedOperationException expected) { }
        System.out.println("MachineBlueprintSpecTest: passed");
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static void reject(String value, int radius, String messagePart) {
        try { MachineBlueprintSpec.parse(json(value), radius); throw new AssertionError("expected invalid blueprint: " + value); }
        catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(messagePart), "wrong validation reason: " + expected.getMessage());
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
