// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;

/** 从现有 plan/execute 公共入口检查 v2 编辑，不让只在内部可用的新字段被公开契约挡住。 */
public final class BuildingModelV2PublicTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        JsonObject request = json("""
                {"goal":{"ability":"maicraft:build","outcome":"把装饰柱定义成组件并复制，保留旧版场景",
                 "parameters":{"operation":"update_scene","scene_id":"01234567-89ab-4cde-8fab-0123456789ab",
                  "edits":{"schema_version":2,"components":{"Column":{"objects":[{"name":"Shaft","type":"MESH","primitive":"cube",
                    "location":[0.5,1.5,0.5],"dimensions":[1,3,1],"material":"Wood"}]}},"remove_components":["Unused"],
                   "objects":[{"name":"Columns","type":"INSTANCE","component":"Column","location":[0,0,0],
                     "mirror":["x"],"material_map":{"Wood":"Trim"},"array":{"count":[3,1,1],"step":[3,0,0],"skip":[[1,0,0]]}},
                    {"name":"Roof","primitive":"wedge","fill":"hollow","wall_thickness":1,"block_state_axes":"minecraft_world",
                     "face_materials":{"front":"Wood"},"edge_material":"Trim","edge_width":1,"open_faces":["back"]}]}}}}
                """);
        for (String tool : new String[]{"plan", "execute"}) {
            var normalized = PublicToolCatalog.validateAndNormalize(tool, request);
            Goal goal = Goal.fromJson(normalized.getAsJsonObject("goal"));
            IntentRuntime.get().compile(goal, 100);
            check(IntentRuntime.isReadOnlyDesign(goal), "更新组件模型不能直接启动走路、取料或放置");
            check(goal.parameters().getAsJsonObject("edits").equals(request.getAsJsonObject("goal").getAsJsonObject("parameters").getAsJsonObject("edits")),
                    "公共规范化不能丢掉版本迁移、组件、镜像或阵列参数");
        }
        for (String badEdits : new String[]{
                "{\"schema_version\":1}",
                "{\"components\":{\"Column\":{\"objects\":[{\"name\":\"Part\",\"type\":\"INSTANCE\",\"component\":\"Other\",\"location\":[0,0,0],\"moves\":[]}]}}}",
                "{\"objects\":[{\"name\":\"Columns\",\"array\":{\"count\":[2,1,1],\"step\":[3,0,0],\"hidden_click\":1}}]}",
                "{\"remove_components\":[\"Same\",\"Same\"]}"}) {
            JsonObject invalid = request.deepCopy(); invalid.getAsJsonObject("goal").getAsJsonObject("parameters").add("edits", json(badEdits));
            try {
                var normalized = PublicToolCatalog.validateAndNormalize("plan", invalid);
                IntentRuntime.get().compile(Goal.fromJson(normalized.getAsJsonObject("goal")), 100);
                throw new AssertionError("公开入口接受了非法嵌套 v2 编辑");
            } catch (IllegalArgumentException expected) { }
        }
        componentQueriesStayReadOnly();
        panelPatternsUseExistingTools();
        check(PublicToolCatalog.definitions().size() == 4, "组件建模沿用已有四个 MCP 入口，不另开执行通道");
        System.out.println("BuildingModelV2PublicTest: passed");
    }
    private static void panelPatternsUseExistingTools() {
        // 创建与编辑网格都走现有公开入口；零开头图案与半砖材质绑定不能在请求规范化时被丢弃。
        var source = json("""
                {"schema_version":2,"coordinate_system":"minecraft_y_up","materials":{
                 "Upper":{"block_id":"minecraft:stone_slab","properties":{"type":"top"}},
                 "Lower":{"block_id":"minecraft:stone_slab","properties":{"type":"bottom"}}},
                 "objects":[{"name":"Screen","type":"MESH","primitive":"panel","location":[2,2,0.5],
                  "dimensions":[4,4,1],"material":"Upper","pattern":{"axes":["x","y"],"rows":["01"],"materials":{"0":"Lower"}}}]}
                """);
        var request = json("{\"goal\":{\"ability\":\"maicraft:build\",\"outcome\":\"设计零开头的半砖网格\",\"parameters\":{\"operation\":\"create_scene\"}}}");
        request.getAsJsonObject("goal").getAsJsonObject("parameters").add("scene",source);
        var edit = json("""
                {"goal":{"ability":"maicraft:build","outcome":"改变窗格的重复图案",
                 "parameters":{"operation":"update_scene","scene_id":"01234567-89ab-4cde-8fab-0123456789ab",
                  "edits":{"objects":[{"name":"Screen","pattern":{"axes":["x","y"],"rows":["01","10"]}}]}}}}
                """);
        for (String tool : new String[]{"plan", "execute"}) for (var value : new JsonObject[]{request,edit}) {
            var goal = Goal.fromJson(PublicToolCatalog.validateAndNormalize(tool,value).getAsJsonObject("goal"));
            IntentRuntime.get().compile(goal,100);
            check(IntentRuntime.isReadOnlyDesign(goal),"网格建模和编辑不能自行开工");
            check(goal.parameters().equals(value.getAsJsonObject("goal").getAsJsonObject("parameters")),"公开入口必须完整保留图案");
        }
        // 嵌套图案中的未知字段也应明确拒绝，不能接受之后又在实际体素编译时无声忽略。
        source.getAsJsonArray("objects").get(0).getAsJsonObject().getAsJsonObject("pattern").addProperty("assume_start_one",true);
        for (String tool : new String[]{"plan","execute"}) {
            try {
                var goal = Goal.fromJson(PublicToolCatalog.validateAndNormalize(tool,request).getAsJsonObject("goal"));
                IntentRuntime.get().compile(goal,100); throw new AssertionError("接受了未声明的图案字段");
            } catch (IllegalArgumentException expected) { }
        }
    }
    private static void componentQueriesStayReadOnly() {
        // 先看固定场景的组件定义再做设计；查询不触发身体动作，且不能拿缺失编号或混用名字代替定义。
        JsonObject request = json("""
                {"goal":{"ability":"maicraft:build","outcome":"检查已保存的柱组件定义",
                 "parameters":{"operation":"get_component_info","scene_id":"01234567-89ab-4cde-8fab-0123456789ab","component_name":"Column"}}}
                """);
        for (String tool : new String[]{"plan", "execute"}) {
            Goal goal = Goal.fromJson(PublicToolCatalog.validateAndNormalize(tool, request).getAsJsonObject("goal"));
            IntentRuntime.get().compile(goal, 100);
            check(IntentRuntime.isReadOnlyDesign(goal), "查询组件定义不能启动施工");
            check(goal.parameters().get("component_name").getAsString().equals("Column"), "查询必须保留精确组件名");
        }
        for (String parameters : new String[]{
                "{\"operation\":\"get_component_info\",\"scene_id\":\"01234567-89ab-4cde-8fab-0123456789ab\"}",
                "{\"operation\":\"get_component_info\",\"component_name\":\"Column\"}",
                "{\"operation\":\"get_component_info\",\"scene_id\":\"01234567-89ab-4cde-8fab-0123456789ab\",\"component_name\":\"Column\",\"object_name\":\"Column\"}",
                "{\"operation\":\"get_scene_info\",\"scene_id\":\"01234567-89ab-4cde-8fab-0123456789ab\",\"component_name\":\"Column\"}"}) {
            JsonObject invalid = request.deepCopy(); invalid.getAsJsonObject("goal").add("parameters", json(parameters));
            try {
                Goal goal = Goal.fromJson(PublicToolCatalog.validateAndNormalize("plan", invalid).getAsJsonObject("goal"));
                IntentRuntime.get().compile(goal, 100);
                throw new AssertionError("组件查询接受了缺失或错用的编号、名称");
            } catch (IllegalArgumentException expected) { }
        }
    }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
