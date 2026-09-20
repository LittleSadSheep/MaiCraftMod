// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.SemanticAbilityCatalog;
import org.maiwithu.maicraft.intent.SemanticContractException;
import com.google.gson.JsonArray;

// 检查公开 MCP 的 plan/execute 接受模型目标，并把创建模型归为不控制身体的操作；知识地址沿用 perceive。
public final class BuildingModelPublicTest {
    public static void main(String[] args) {
        requiresAuthoredBlueprint();
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

    private static void requiresAuthoredBlueprint() {
        // 两个公开提交入口都必须在角色接单前拒绝空设计和旧模板参数，不能只收紧内部施工方法。
        String saved = "01234567-89ab-4cde-8fab-0123456789ab";
        String blueprint = "{\"blueprint\":{\"blocks\":[{\"offset\":[0,0,0],\"block_id\":\"minecraft:stone\"}]}}";
        var legacy = JsonParser.parseString("""
                {"purpose":"house","size":"small","style":"wooden","features":["windows"],
                 "terrain_fit":"surface","preferred_materials":["minecraft:oak_planks"]}
                """).getAsJsonObject();
        for (String ability : new String[]{"maicraft:build", "maicraft:design_build"}) {
            var fields = SemanticAbilityCatalog.describe(ability).getAsJsonObject("parameters");
            for (String key : legacy.keySet())
                if (fields.has(key)) throw new AssertionError("public contract still advertises template field " + key);
            for (String tool : new String[]{"plan", "execute"}) {
                rejects(tool, ability, "{}", "missing_build_blueprint");
                rejects(tool, ability, "{\"material_policy\":\"specified\"}", "missing_build_blueprint");
                for (String key : legacy.keySet()) {
                    var parameters = new JsonObject(); parameters.add(key, legacy.get(key));
                    rejects(tool, ability, parameters.toString(), "unknown_parameter");
                }
                // 场景编号与逐格蓝图均可直接施工；project_id 只恢复冻结施工单，不重新计算房屋布局。
                compile(tool, ability, blueprint);
                compile(tool, ability, "{\"scene_id\":\"" + saved + "\"}");
                compile(tool, ability, "{\"project_id\":\"" + saved + "\"}");
                rejects(tool, ability, "{\"project_id\":\"" + saved + "\",\"material_policy\":\"ordinary\"}",
                        "invalid_build_resume");
                rejects(tool, ability, "{\"operation\":\"preview\"}", null);
                rejects(tool, ability, "{\"blueprint\":{\"blocks\":[]}}", null);
                var mixed = JsonParser.parseString(blueprint).getAsJsonObject(); mixed.addProperty("size", "small");
                rejects(tool, ability, mixed.toString(), "unknown_parameter");
            }
        }
        // 组合目标也逐个检查子建筑，不能把没有图纸的建造藏进 sequence 后等角色走到现场才发现。
        var sequence = request("maicraft:sequence", "{}");
        var children = new JsonArray();
        children.add(request("maicraft:build", "{}").getAsJsonObject("goal"));
        sequence.getAsJsonObject("goal").add("children", children);
        try {
            compileRequest("plan", sequence);
            throw new AssertionError("sequence accepted construction without a blueprint");
        } catch (SemanticContractException expected) {
            if (!"goal.children[0].parameters".equals(expected.path())) throw new AssertionError(expected);
        }
    }

    private static void rejects(String tool, String ability, String parameters, String code) {
        // 缺图纸必须给出可识别的契约错误，且不能把其他异常或测试自身的断言当作成功拒绝。
        try { compile(tool, ability, parameters); }
        catch (IllegalArgumentException expected) {
            if (code != null && (!(expected instanceof SemanticContractException violation)
                    || !code.equals(violation.violationCode()))) throw new AssertionError(expected);
            return;
        }
        throw new AssertionError("accepted building without a valid authored design: " + parameters);
    }

    private static void compile(String tool, String ability, String parameters) {
        compileRequest(tool, request(ability, parameters));
    }

    private static void compileRequest(String tool, JsonObject request) {
        var normalized = PublicToolCatalog.validateAndNormalize(tool, request);
        IntentRuntime.get().compile(Goal.fromJson(normalized.getAsJsonObject("goal")), 100);
    }

    private static JsonObject request(String ability, String parameters) {
        var goal = new JsonObject(); goal.addProperty("ability", ability);
        goal.addProperty("outcome", "按作者蓝图建造或预览");
        goal.add("parameters", JsonParser.parseString(parameters));
        var request = new JsonObject(); request.add("goal", goal); return request;
    }
}
