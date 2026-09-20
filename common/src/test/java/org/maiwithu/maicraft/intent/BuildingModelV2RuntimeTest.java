// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.preview.PreviewSession;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneCompiler;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneExport;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneStore;
import org.maiwithu.maicraft.core.tools.work.BuildTool;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.maiwithu.maicraft.task.TaskResult;

/** 从模型操作走到实际预览和施工参数，核对同一份v2蓝图；测试不提交建造身体任务或制造材料。 */
public final class BuildingModelV2RuntimeTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var directory = Files.createTempDirectory("model-v2-runtime-"); var store = new BuildingSceneStore(directory, "f".repeat(64));
        var preview = new AtomicReference<PreviewSession>();
        JsonObject source = json("""
                {"schema_version":2,"coordinate_system":"minecraft_y_up","materials":{
                 "Body":{"block_id":"minecraft:stone_bricks"},"Trim":{"block_id":"minecraft:smooth_quartz"}},
                 "components":{"Gable":{"objects":[{"name":"Panel","type":"MESH","primitive":"triangle",
                  "location":[3.5,2,0.5],"dimensions":[7,4,1],"material":"Body","edge_material":"Trim"}]}},
                 "objects":[{"name":"Panels","type":"INSTANCE","component":"Gable","location":[0,0,0],
                  "array":{"count":[2,1,1],"step":[8,0,0]}}]}
                """);
        try (var h = new InteractionWorldTestHarness()) {
            JsonObject create = json("{\"operation\":\"create_scene\"}"); create.add("scene", source);
            var first = report(BuildingSceneAdapter.adapt(goal(create,true),h.player,null,session -> { throw new AssertionError("保存不能弹预览或开工"); },() -> store));
            String id = (String) first.data().get("scene_id");
            check(((Number)first.data().get("block_count")).intValue() == 32 && Boolean.FALSE.equals(first.data().get("construction_started")),
                    "组件模型通过真实入口保存，但只报告32个最终目标，不开始施工");
            var query = parameters("get_component_info",id); query.addProperty("component_name","Gable");
            var definition = report(BuildingSceneAdapter.adapt(goal(query,false),h.player,null,session -> false,() -> store));
            check(definition.data().containsKey("definition") && Boolean.FALSE.equals(definition.data().get("local_paths_are_scene_queries")),
                    "公共组件查询返回定义，并明确局部路径不属于世界中的实例");
            query = parameters("get_object_info",id); query.addProperty("object_name","Panels[1,0,0]/Panel"); query.addProperty("page",0);
            var info = report(BuildingSceneAdapter.adapt(goal(query,false),h.player,null,session -> false,() -> store));
            check(info.data().containsKey("surface_faces") && info.data().containsKey("surface_edges"), "公开对象查询提供真实图元面棱资料");

            report(BuildingSceneAdapter.adapt(goal(parameters("preview",id),false),h.player,null,session -> { preview.set(session); return true; },() -> store));
            check(preview.get() != null && preview.get().designOnly() && !preview.get().confirm(), "确认只读预览也不能把它变成施工命令");
            IntentAction action = BuildingSceneAdapter.adapt(goal(parameters("build",id),false),h.player,null,session -> false,() -> store);
            check(action instanceof IntentAction.Tool, "只有明确build才产生施工工具请求");
            var tool = (IntentAction.Tool) action; JsonObject argsJson = json(tool.argumentsJson());
            var targets = BuildTool.resolvedTargets(argsJson.getAsJsonArray("ops"),true);
            var actual = new LinkedHashMap<BlockPos,BlockState>(); targets.forEach(target -> actual.put(target.pos(),target.desiredState()));
            check(tool.toolName().equals("build") && actual.equals(preview.get().cells()) && targets.size() == 32,
                    "真实预览与施工参数逐格一致，包含固定锚点和精确的描边材质");
            check(!argsJson.get("replace_existing").getAsBoolean() && !argsJson.get("broaden_material_families").getAsBoolean(),
                    "新建模能力没有顺带批准拆旧建筑或替换指定材料");

            var compiled = BuildingSceneCompiler.compile(source);
            var file = BuildingSceneExport.write(directory,id,compiled,"json");
            check(JsonParser.parseString(Files.readString(file)).equals(compiled), "JSON导出与同一模型的编译结果一致");
            var nbt = BuildingSceneExport.structure(compiled);
            check(nbt.getList("blocks",Tag.TAG_COMPOUND).size() == 32, "NBT也保留同一批最终目标");
            var edit = parameters("update_scene",id);
            edit.add("edits",json("{\"objects\":[{\"name\":\"Panels\",\"array\":{\"count\":[3,1,1],\"step\":[8,0,0]}}]}"));
            var revised = report(BuildingSceneAdapter.adapt(goal(edit,false),h.player,null,session -> false,() -> store));
            try {
                BuildingSceneAdapter.adapt(goal(parameters("preview",(String)revised.data().get("scene_id")),false),h.player,null,
                        session -> { throw new AssertionError("未加载的第三份模型不应发布预览"); },() -> store);
                throw new AssertionError("预览把未加载的区块当成已观察现场");
            } catch (IllegalArgumentException expected) { }
            check(h.blockUses() == 0 && h.itemUses() == 0, "建模、查询、导出、预览和生成施工参数全程没有游戏操作");
            patternPreviewBuildAndEdit(store,h);
        }
        System.out.println("BuildingModelV2RuntimeTest: public scene operations, native preview, exact build arguments and exports passed");
    }
    private static void patternPreviewBuildAndEdit(BuildingSceneStore store, InteractionWorldTestHarness h) {
        // 先保存留孔面板，再改成上下半砖；每份版本的预览、导出和施工都必须保留同一份最终方块要求。
        var source = json("""
                {"schema_version":2,"coordinate_system":"minecraft_y_up","materials":{
                 "Body":{"block_id":"minecraft:stone_bricks"},
                 "Upper":{"block_id":"minecraft:stone_slab","properties":{"type":"top"}},
                 "Lower":{"block_id":"minecraft:stone_slab","properties":{"type":"bottom"}}},
                 "objects":[{"name":"Screen","type":"MESH","primitive":"panel","location":[2,2,0.5],
                  "dimensions":[4,4,1],"material":"Body","pattern":{"axes":["x","y"],"rows":["01","10"]}}]}
                """);
        var create = json("{\"operation\":\"create_scene\"}"); create.add("scene",source);
        var created = report(BuildingSceneAdapter.adapt(goal(create,true),h.player,null,session -> false,() -> store));
        String original = created.data().get("scene_id").toString(), id = original;
        for (int version = 0; version < 2; version++) {
            var preview = new AtomicReference<PreviewSession>();
            report(BuildingSceneAdapter.adapt(goal(parameters("preview",id),false),h.player,null,session -> { preview.set(session); return true; },() -> store));
            var action = (IntentAction.Tool) BuildingSceneAdapter.adapt(goal(parameters("build",id),false),h.player,null,session -> false,() -> store);
            var arguments = action.arguments(); var targets = BuildTool.resolvedTargets(arguments.getAsJsonArray("ops"),true);
            var actual = new LinkedHashMap<BlockPos,BlockState>(); targets.forEach(target -> actual.put(target.pos(),target.desiredState()));
            check(targets.size() == 16 && actual.equals(preview.get().cells()),"预览与施工须包括相同空气格或半砖状态");
            check(!arguments.get("replace_existing").getAsBoolean(),"图案留孔不能暗中批准挖掉现场已有方块");
            var first = actual.get(new BlockPos(0,1,0)); var second = actual.get(new BlockPos(1,1,0));
            if (version == 0) check(first.isAir() && second.is(Blocks.STONE_BRICKS),"零开头面板应先留孔再放方块");
            else check(first.getValue(BlockStateProperties.SLAB_TYPE)
                            == SlabType.BOTTOM
                    && second.getValue(BlockStateProperties.SLAB_TYPE)
                            == SlabType.TOP,"半砖版本应以下半砖开头");
            var saved = store.load(id,"minecraft:overworld");
            var compiled = BuildingSceneCompiler.compile(saved.scene());
            check(BuildingSceneExport.structure(compiled).getList("blocks",Tag.TAG_COMPOUND).size() == 16,"导出不丢掉网格的留孔或半砖目标");
            if (version == 0) {
                var edit = parameters("update_scene",id);
                edit.add("edits",json("{\"objects\":[{\"name\":\"Screen\",\"material\":\"Upper\",\"pattern\":{\"axes\":[\"x\",\"y\"],\"rows\":[\"01\"],\"materials\":{\"0\":\"Lower\"}}}]}"));
                id = report(BuildingSceneAdapter.adapt(goal(edit,false),h.player,null,session -> false,() -> store)).data().get("scene_id").toString();
            }
        }
        check(store.load(original,"minecraft:overworld").scene().equals(source),"改成半砖时原来的留孔场景版本仍须保留");
        check(h.blockUses() == 0 && h.itemUses() == 0,"网格建模和预览不会实际取料或放置");
    }
    private static JsonObject parameters(String operation,String id) { JsonObject value = new JsonObject(); value.addProperty("operation",operation); value.addProperty("scene_id",id); return value; }
    private static Goal goal(JsonObject parameters,boolean target) {
        JsonObject root = json("{\"ability\":\"maicraft:build\",\"outcome\":\"验证可复用三角山墙\"}"); root.add("parameters",parameters);
        if (target) root.add("target",json("{\"kind\":\"coordinates\",\"position\":{\"x\":0,\"y\":1,\"z\":0,\"dimension\":\"minecraft:overworld\"}}"));
        Goal goal = Goal.fromJson(root); IntentRuntime.get().compile(goal,100); return goal;
    }
    private static TaskResult report(IntentAction action) {
        check(action instanceof IntentAction.Report, "只读模型操作必须返回报告"); var result = ((IntentAction.Report)action).result();
        check(result.success(), "模型操作应成功: " + result.message()); return result;
    }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
