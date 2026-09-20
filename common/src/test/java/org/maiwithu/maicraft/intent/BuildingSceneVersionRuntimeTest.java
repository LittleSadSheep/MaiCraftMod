// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.util.List;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.blueprint.BuildingModelContract;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneStore;
import org.maiwithu.maicraft.task.TaskResult;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** 使用真实建模适配器验证版本凭据与不可变修订；拒绝时不能产生可执行施工请求或新场景文件。 */
public final class BuildingSceneVersionRuntimeTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var root = Files.createTempDirectory("versioned-building-runtime-"); String world = "e".repeat(64), dimension = "minecraft:overworld";
        var store = new BuildingSceneStore(root,world); var contract = BuildingModelContract.current();
        var source = json("""
                {"schema_version":2,"coordinate_system":"minecraft_y_up","materials":{"Wall":{"block_id":"minecraft:stone"}},
                 "objects":[{"name":"Wall","type":"MESH","primitive":"panel","location":[1,1,0.5],"dimensions":[2,2,1],"material":"Wall"}]}
                """);
        var examples = new JsonObject();
        try (var h = new InteractionWorldTestHarness()) {
            var create = guarded("create_scene",null); create.add("scene",source);
            var invalid = create.deepCopy(); invalid.addProperty(BuildingModelContract.EXPECTED_SCHEMA,"outdated");
            fails(() -> run(invalid,true,store,h),"building_contract_changed");
            var created = report(run(create,true,store,h)); String original = created.data().get("scene_id").toString();
            check(created.data().get("capability_revision").equals(contract.revision())
                    && created.data().get("design_schema_revision").equals(contract.designSchemaRevision()),"真实创建结果必须返回校验版本");
            examples.add("create_goal",goal(create,true).toJson()); examples.add("create_terminal_result",json(created.toJson()));

            var update = guarded("update_scene",original);
            update.add("edits",json("{\"objects\":[{\"name\":\"Wall\",\"dimensions\":[4,2,1],\"location\":[2,1,0.5]}]}"));
            var revised = report(run(update,false,store,h)); String id = revised.data().get("scene_id").toString();
            check(revised.data().get("parent_scene_id").equals(original) && !id.equals(original),"修改发布子版本并保留父编号");
            check(store.load(original,dimension).scene().equals(source),"修改不能覆盖原来的作者模型");
            examples.add("update_goal",goal(update,false).toJson()); examples.add("update_terminal_result",json(revised.toJson()));

            var preview = report(run(guarded("preview",id),false,store,h));
            check(Boolean.TRUE.equals(preview.data().get("preview_created")) && Boolean.FALSE.equals(preview.data().get("construction_started")),"版本核对成功的预览也不能开始施工");
            var build = guarded("build",id); var action = run(build,false,store,h);
            check(action instanceof IntentAction.Tool tool && tool.toolName().equals("build")
                    && tool.arguments().getAsJsonArray("ops").size() == 8,"只有版本一致的场景才生成完整施工参数");
            examples.add("build_goal",goal(build,false).toJson());
            examples.add("build_internal_arguments",((IntentAction.Tool)action).arguments());

            var directory = root.resolve("build-scenes").resolve(world); var path = directory.resolve(id+".json");
            String intact = Files.readString(path);
            var badEdit = guarded("update_scene",id); badEdit.add("edits",json("{\"objects\":[{\"name\":\"Wall\",\"dimensions\":[4,0,1]}]}"));
            fails(() -> run(badEdit,false,store,h),"dimensions");
            check(Files.readString(path).equals(intact),"无效修改不能截断或覆盖旧场景");
            try (var files = Files.list(directory)) { check(files.count() == 2,"失败时不得发布第三份场景"); }

            // 在测试文件中模拟契约升级与旧文件；严格预览/施工/编辑均拒绝，未携带版本的旧调用仍可读取并使用。
            var legacy = json(intact); legacy.remove("capability_revision"); legacy.remove("design_schema_revision"); Files.writeString(path,legacy.toString());
            for (String operation : List.of("preview","build","get_scene_info")) fails(() -> run(guarded(operation,id),false,store,h),"building_scene_revalidation_required");
            fails(() -> run(guardedUpdate(id),false,store,h),"building_scene_revalidation_required");
            check(run(json("{\"operation\":\"build\",\"scene_id\":\""+id+"\"}"),false,store,h) instanceof IntentAction.Tool,"未要求版本的既有客户端仍可使用旧记录");
            var fresh = guarded("create_scene",null); fresh.add("scene",store.load(id,dimension).scene());
            var revalidated = report(run(fresh,true,store,h));
            check(!id.equals(revalidated.data().get("scene_id")) && Files.readString(path).equals(legacy.toString()),"旧模型重校验必须另存新编号");
            check(h.blockUses() == 0 && h.itemUses() == 0,"创建、核对、预览和生成参数都没有实际世界操作");
        }
        String output = System.getProperty("maicraft.building.contract.output");
        if (output != null) { var directory = Path.of(output); Files.createDirectories(directory); Files.writeString(directory.resolve("operation-examples.json"),examples.toString()); }
        System.out.println("BuildingSceneVersionRuntimeTest: guarded create/update/preview/build and legacy revalidation passed");
    }
    private static JsonObject guardedUpdate(String id) { var value = guarded("update_scene",id); value.add("edits",json("{\"objects\":[{\"name\":\"Wall\",\"material\":\"Wall\"}]}")); return value; }
    private static JsonObject guarded(String operation,String id) {
        var current = BuildingModelContract.current(); var value = new JsonObject(); value.addProperty("operation",operation);
        if (id != null) value.addProperty("scene_id",id);
        value.addProperty(BuildingModelContract.EXPECTED_CAPABILITY,current.revision()); value.addProperty(BuildingModelContract.EXPECTED_SCHEMA,current.designSchemaRevision()); return value;
    }
    private static Goal goal(JsonObject parameters,boolean target) {
        return new Goal("maicraft:build","验证版本化建筑设计",target ? new Goal.SemanticTarget("coordinates",null,new Goal.WorldPosition(0,1,0,"minecraft:overworld"),null) : null,
                parameters.toString(),"{}",List.of(),List.of());
    }
    private static IntentAction run(JsonObject parameters,boolean target,BuildingSceneStore store,InteractionWorldTestHarness h) {
        var goal = goal(parameters,target); IntentRuntime.get().compile(goal,100);
        return BuildingSceneAdapter.adapt(goal,h.player,null,session -> true,() -> store);
    }
    private static TaskResult report(IntentAction action) { check(action instanceof IntentAction.Report,"设计操作必须返回报告"); var result = ((IntentAction.Report)action).result(); check(result.success(),result.message()); return result; }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static void fails(Runnable call,String detail) { try { call.run(); } catch (IllegalArgumentException error) { check(error.getMessage().contains(detail),error.getMessage()); return; } throw new AssertionError("本应拒绝: "+detail); }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
