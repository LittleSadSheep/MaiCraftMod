// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.blueprint.BuildingModelContract;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneStore;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.mcp.MaiCraftRuntimeFacade;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskState;
import sun.misc.Unsafe;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;

/** 走公开执行的接管边界与真实 IntentRuntime，确保身体施工中也能保存、改图和查询而不抢占角色。 */
public final class BuildingDesignConcurrencyTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var root = Files.createTempDirectory("concurrent-building-design-"); String world = "9".repeat(64);
        var store = new BuildingSceneStore(root,world);
        var constructor = IntentRuntime.class.getDeclaredConstructor(); constructor.setAccessible(true);
        var runtime = constructor.newInstance(); field(IntentRuntime.class,"stateIdentity").set(runtime,new StateIdentity(world,root));
        var brainField = field(CompanionTickDispatcher.class,"brain"); Object previous = brainField.get(null);
        var memory = (Unsafe)field(Unsafe.class,"theUnsafe").get(null);
        var brainType = Class.forName("org.maiwithu.maicraft.task.CompanionBrain"); var slotType = Class.forName("org.maiwithu.maicraft.task.TaskSlot");
        Object brain = memory.allocateInstance(brainType), slot = memory.allocateInstance(slotType);
        var body = new IntentTaskRecord(UUID.randomUUID(),null,new Goal("maicraft:build","保留当前施工",null,
                "{\"project_id\":\"01234567-89ab-4cde-8fab-0123456789ab\"}","{}",List.of(),List.of()));
        body.setState(TaskState.RUNNING); field(slotType,"record").set(slot,body); field(brainType,"current").set(brain,slot);
        brainField.set(null,brain);
        try (var h = new InteractionWorldTestHarness()) {
            var bodyPosition = h.player.position();
            var create = parameters("create_scene",null);
            create.add("scene",json("""
                    {"schema_version":2,"coordinate_system":"minecraft_y_up","materials":{"Wall":{"block_id":"minecraft:stone"}},
                     "components":{"Bay":{"objects":[{"name":"Segment","type":"MESH","primitive":"panel","location":[1,1,0.5],"dimensions":[2,2,1],"material":"Wall"}]}},
                     "objects":[{"name":"Wall","type":"MESH","primitive":"panel","location":[1,1,0.5],"dimensions":[2,2,1],"material":"Wall"}]}
                    """));
            var created = execute(runtime,store,h,create,true,"design:create");
            check(execute(runtime,store,h,create,true,"design:create") == created,"同一设计操作幂等重试必须返回原任务");
            String id = data(created).get("scene_id").getAsString();
            var update = parameters("update_scene",id); update.add("edits",json("{\"objects\":[{\"name\":\"Wall\",\"location\":[3,1,0.5]}]}"));
            var revised = execute(runtime,store,h,update,false,"design:update");
            check(data(revised).get("parent_scene_id").getAsString().equals(id),"并行改图仍建立不可变父子版本");
            id = data(revised).get("scene_id").getAsString();
            for (String operation : List.of("get_scene_info","get_object_info","get_component_info","preview")) {
                var query = parameters(operation,id);
                if (operation.equals("get_object_info")) query.addProperty("object_name","Wall");
                if (operation.equals("get_component_info")) query.addProperty("component_name","Bay");
                execute(runtime,store,h,query,false,"design:"+operation);
                check(brainField.get(null) == brain && field(brainType,"current").get(brain) == slot
                        && field(slotType,"record").get(slot) == body && body.getState() == TaskState.RUNNING,
                        "纯设计操作不能取消、替换或接管已有身体任务");
            }
            check(h.blockUses() == 0 && h.itemUses() == 0,"并行设计不能执行方块或物品操作");
            check(h.player.position().equals(bodyPosition) && h.inventory.isEmpty(),"纯设计不能移动角色或取得施工材料");
            // design_build 即便手工写 operation=build 也不能变成子 Agent 的施工后门。
            try { runtime.compile(goal(parameters("build",id),false),100); throw new AssertionError("只读能力启动了施工"); }
            catch (IllegalArgumentException expected) { }
        } finally { brainField.set(null,previous); }
        System.out.println("BuildingDesignConcurrencyTest: read-only model operations retain the active body task");
    }
    private static IntentTaskRecord execute(IntentRuntime runtime,BuildingSceneStore store,InteractionWorldTestHarness h,
                                           JsonObject parameters,boolean target,String key) throws Exception {
        var goal = goal(parameters,target);
        var boundary = MaiCraftRuntimeFacade.class.getDeclaredMethod("dispatchExecution",Goal.class,LocalPlayer.class,Supplier.class);
        boundary.setAccessible(true);
        var record = (IntentTaskRecord)boundary.invoke(null,goal,h.player,
                (Supplier<IntentTaskRecord>)() -> runtime.execute(h.player,goal,null,key,preview -> true,() -> store));
        check(record.getState() == TaskState.SUCCESS && record.terminalSnapshot() != null,"设计操作必须保留可查询的真实终态: "+record.describe());
        return record;
    }
    private static Goal goal(JsonObject parameters,boolean target) {
        return new Goal("maicraft:design_build","身体施工期间编辑设计",target ? new Goal.SemanticTarget("coordinates",null,new Goal.WorldPosition(0,1,0,"minecraft:overworld"),null) : null,
                parameters.toString(),"{}",List.of(),List.of());
    }
    private static JsonObject parameters(String operation,String id) {
        var value = new JsonObject(); value.addProperty("operation",operation); if (id != null) value.addProperty("scene_id",id);
        var current = BuildingModelContract.current(); value.addProperty(BuildingModelContract.EXPECTED_CAPABILITY,current.revision());
        value.addProperty(BuildingModelContract.EXPECTED_SCHEMA,current.designSchemaRevision()); return value;
    }
    private static JsonObject data(IntentTaskRecord record) { return record.terminalSnapshot().result().getAsJsonObject("data"); }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static Field field(Class<?> type,String name) throws Exception { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
