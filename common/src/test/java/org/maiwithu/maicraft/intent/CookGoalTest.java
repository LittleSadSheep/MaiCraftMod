// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.tools.work.SemanticCookApi;

/** 要烧到三百件就保留三百，原料许可与燃料清单也不能被宽松类型转换改成另一种行动。 */
public final class CookGoalTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (String patch : List.of("{\"count\":1.5}", "{\"count\":\"2\"}", "{\"count\":0}",
                "{\"count\":2305}", "{\"count\":null}", "{\"allow_harm\":\"true\"}",
                "{\"allowed_sources\":[\"cook\"]}", "{\"allowed_fuels\":[true]}", "{\"recipe_preference\":42}",
                "{\"recipe_preference\":\"unknown\"}", "{\"item_id\":null}", "{\"source_hint\":{}}")) {
            try {
                SemanticCookApi.parse(arguments(patch));
                throw new AssertionError("烹饪接受了含糊参数: " + patch);
            } catch (IllegalArgumentException expected) { }
        }
        for (int count : List.of(1, 256, 300, 2304)) {
            var record = SemanticCookApi.newRecord(new ToolContext("cook-contract", 0), arguments("{\"count\":" + count + "}"));
            if (record.count != count) throw new AssertionError("总目标在内部工具入口被改变");
        }
        System.out.println("CookGoalTest: passed");
    }

    static JsonObject arguments(String patch) {
        var args = new JsonObject();
        args.addProperty("item_id", "minecraft:iron_ingot");
        JsonParser.parseString(patch).getAsJsonObject().entrySet().forEach(entry -> args.add(entry.getKey(), entry.getValue()));
        return args;
    }
}
