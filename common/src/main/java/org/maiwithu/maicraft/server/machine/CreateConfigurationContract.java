// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;

/** 配置动作的名称、必填字段与数值范围同时用于原生入口和设计资料，避免模型猜配置参数。 */
public final class CreateConfigurationContract {
    public static final List<String> ACTIONS = List.of("create.speed", "create.filter");
    private static final int MIN_SPEED = -256, MAX_SPEED = 256;
    private CreateConfigurationContract() {}
    public static JsonObject describe() {
        JsonObject result = JsonParser.parseString("""
                {"operation":"machine.configure","position_binding":"production.configurations references its node offset",
                 "actions":{
                  "create.speed":{"required":["action","value"],"target":"native rotation speed controller","value":{"type":"integer"}},
                  "create.filter":{"required":["action","side"],"target":"active native FilteringBehaviour",
                   "criteria":"item_id plus optional components, or clear=true","side":["up","down","north","south","east","west"],
                   "effects":"Only changes filtering criteria; does not insert ingredients. Advanced filter items may require native material consumption."}},
                 "verification":"Read machine.configuration after execution; a declared configuration is not verified use."}
                """).getAsJsonObject();
        var range = result.getAsJsonObject("actions").getAsJsonObject("create.speed").getAsJsonObject("value");
        range.addProperty("minimum", MIN_SPEED); range.addProperty("maximum", MAX_SPEED);
        JsonArray names = new JsonArray(); ACTIONS.forEach(names::add); result.add("supported_actions", names); return result;
    }
    static int speedValue(JsonObject body) { return ServerAccess.integer(body, "value", MIN_SPEED, MAX_SPEED); }
    static void validate(JsonObject body) {
        String action = ServerAccess.text(body, "action");
        if (!ACTIONS.contains(action)) throw ServerAccess.denied("unsupported", "Unsupported Create action");
        if (action.equals("create.speed")) speedValue(body);
        else {
            ServerAccess.side(body);
            if (!(body.has("clear") && ServerAccess.bool(body, "clear")) && !body.has("item_id") && !body.has("player_slot"))
                throw ServerAccess.denied("invalid_argument", "Specify a filter item_id or clear=true");
        }
    }
}
