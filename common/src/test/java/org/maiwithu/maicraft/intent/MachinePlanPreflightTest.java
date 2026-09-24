// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** 无玩家、库存或动力时仍可检查蓝图；非法部件和不完整原生安装在 plan 返回具体修订问题。 */
public final class MachinePlanPreflightTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var valid = review("{\"offset\":[0,0,0],\"block_id\":\"minecraft:barrel\",\"properties\":{\"facing\":\"up\"}}", "");
        check(valid.get("valid").getAsBoolean(), "a physical blueprint does not require carried materials or live power");
        var details = valid.getAsJsonArray("checks").get(0).getAsJsonObject();
        check(details.getAsJsonObject("native_material_counts").get("minecraft:barrel").getAsInt() == 1,
                "plan lists real placement materials without acquiring them");
        check(details.get("physical_layout_compiled").getAsBoolean(), "plan reaches the native installation compiler");
        check(!review("{\"offset\":[0,0,0],\"block_id\":\"missing:block\"}", "").get("valid").getAsBoolean(), "unknown block rejected before execution");
        check(!review("{\"offset\":[0,0,0],\"block_id\":\"minecraft:barrel\",\"properties\":{\"facing\":\"diagonal\"}}", "").get("valid").getAsBoolean(), "unsupported state rejected before execution");
        var door = review("{\"offset\":[0,0,0],\"block_id\":\"minecraft:oak_door\",\"properties\":{\"half\":\"lower\"}}", "");
        check(!door.get("valid").getAsBoolean() && door.toString().contains("issues"), "undeclared generated half returns an actionable installation diagnostic");
        check(!review("{\"offset\":[0,0,0],\"block_id\":\"minecraft:barrel\"}", ",\"constraints\":{\"forbidden_mods\":[\"minecraft\"]}").get("valid").getAsBoolean(), "forbidden namespaces are enforced for every blueprint");
        System.out.println("MachinePlanPreflightTest: passed");
    }

    private static JsonObject review(String blocks, String extra) {
        JsonObject request = JsonParser.parseString("""
                {"ability":"maicraft:build_machine","outcome":"build on the observed platform",
                 "target":{"kind":"landmark","label":"site"},
                 "parameters":{"snapshot_id":"site-receipt","allow_modify":true}}
                """).getAsJsonObject();
        request.getAsJsonObject("parameters").add("blueprint", JsonParser.parseString("{\"blocks\":[" + blocks + "]" + extra + "}"));
        return MachinePlanPreflight.review(Goal.fromJson(request));
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
