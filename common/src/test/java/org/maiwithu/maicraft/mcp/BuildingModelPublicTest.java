// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;

// 检查公开 MCP 的 plan/execute 接受模型目标，并把创建模型归为不控制身体的操作；知识地址沿用 perceive。
public final class BuildingModelPublicTest {
    public static void main(String[] args) {
        if (PublicToolCatalog.definitions().size() != 4)
            throw new AssertionError("modelling must remain inside the existing four MCP tools");
        JsonObject request = JsonParser.parseString("""
                {"goal":{"ability":"maicraft:build","outcome":"Model a wall","target":{"kind":"current_place"},
                  "parameters":{"operation":"create_scene","scene":{"materials":{"Wall":{"block_id":"minecraft:oak_planks"}},
                    "objects":[{"name":"Wall","type":"MESH","primitive":"cube","location":[3.5,0.5,2],
                                "dimensions":[7,1,4],"material":"Wall"}]}}}}
                """).getAsJsonObject();
        // 同时过公开请求校验和语义编译，避免只测试内部辅助方法能成功，却漏掉最外层拒绝。
        for (String tool : new String[]{"plan", "execute"}) {
            JsonObject validated = PublicToolCatalog.validateAndNormalize(tool, request);
            var goal = Goal.fromJson(validated.getAsJsonObject("goal"));
            IntentRuntime.get().compile(goal, 100);
            if (!IntentRuntime.isReadOnlyDesign(goal)) throw new AssertionError("create_scene cannot start body work");
        }
        request = JsonParser.parseString("""
                {"view":"knowledge","resource_uri":"maicraft://knowledge/build/scene/01234567-89ab-4cde-8fab-0123456789ab"}
                """).getAsJsonObject();
        PublicToolCatalog.validateAndNormalize("perceive", request);
        System.out.println("BuildingModelPublicTest: existing MCP tools accept model authoring and resource reads");
    }
}
