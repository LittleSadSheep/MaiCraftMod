// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.tools.work.SemanticAcquireApi;
import org.maiwithu.maicraft.core.tools.work.SemanticAcquireTool;

/** 玩家要求多少就保留多少；不把小数、文本布尔值和无法理解的来源提示悄悄转成另一种行动。 */
public final class AcquireGoalTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (String patch : List.of("{\"count\":1.5}", "{\"count\":0}", "{\"count\":-1}",
                "{\"count\":2305}", "{\"count\":2147483648}", "{\"count\":\"2\"}", "{\"count\":null}",
                "{\"radius\":1.5}", "{\"radius\":0}", "{\"radius\":49}", "{\"radius\":[]}",
                "{\"allow_harm\":\"true\"}", "{\"allow_harm\":1}", "{\"allow_harm\":null}",
                "{\"item_ids\":[1]}", "{\"item_tag\":true}", "{\"protected_labels\":[false]}",
                "{\"allowed_sources\":[\"magic\"]}", "{\"allowed_sources\":null}",
                "{\"source_hint\":false}", "{\"source_hint\":{\"position\":[1,2,3]}}",
                "{\"source_hint\":{\"description\":42}}", "{\"source_hint\":{\"block_ids\":[true]}}")) {
            try {
                record(arguments(patch));
                throw new AssertionError("取物入口接受了有歧义的参数: " + patch);
            } catch (IllegalArgumentException expected) { }
        }
        for (int count : List.of(1, 256, 257, SemanticAcquireTaskRecord.MAX_FINAL_COUNT)) {
            var request = record(arguments("{\"count\":" + count + ",\"radius\":48,\"allow_harm\":false}"));
            check(request.count == count && request.searchRadius == 48 && !request.allowHarm,
                    "合法目标数量和半径必须原样保留");
        }
        var defaults = record(arguments("{}"));
        check(defaults.count == 1 && defaults.searchRadius == 16 && !defaults.allowHarm, "缺省仍是一件物品、不伤害生物");
        var properties = (Map<?, ?>) new SemanticAcquireTool().parameterSchema().get("properties");
        check(((Map<?, ?>) properties.get("count")).get("maximum").equals(SemanticAcquireTaskRecord.MAX_FINAL_COUNT),
                "工具发现与实际执行必须使用相同数量上限");
        System.out.println("AcquireGoalTest: passed");
    }

    private static JsonObject arguments(String patch) {
        var result = new JsonObject();
        result.addProperty("item_id", "minecraft:dirt");
        JsonParser.parseString(patch).getAsJsonObject().entrySet().forEach(entry -> result.add(entry.getKey(), entry.getValue()));
        return result;
    }

    private static SemanticAcquireTaskRecord record(JsonObject arguments) {
        return SemanticAcquireApi.newRecord(new ToolContext("acquire-contract", 0), arguments, null);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
