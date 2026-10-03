package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.task.physics.PhysicalControlParameters;
import org.maiwithu.maicraft.core.task.physics.PhysicalControlTaskRecord;

/** 模型指定设置意图；执行器自己走位、选工具、检查原生面板命中和服务器回执。 */
final class PhysicalControlAbilityAdapter {
    static final String ABILITY="maicraft:physical_control";
    private PhysicalControlAbilityAdapter() {}
    static void validate(Goal goal) {
        var parameters=PhysicalControlParameters.parse(goal.parameters());
        PhysicalAssemblyAbilityAdapter.validateTarget(goal,parameters.structureId());
    }
    static IntentAction adapt(Goal goal,LocalPlayer player,IntentRuntime runtime) {
        validate(goal);var p=PhysicalControlParameters.parse(goal.parameters());
        var anchor=PhysicalAssemblyAbilityAdapter.anchor(goal,player,runtime,p.structureId());
        return new IntentAction.Native(new PhysicalControlTaskRecord("physical-control-"+UUID.randomUUID(),player.level().getGameTime()+20*60*15,
                p,anchor,player.level().dimension().location().toString()));
    }
    static JsonObject contract() {
        var out=new JsonObject();out.addProperty("summary","起飞/行驶前读取或设置物理部件：Create 电机/转速控制器旋钮、Simulated 油门信号、Create 红石链路收发模式与两项频率。使用真实走位、命中、物品与原生协议，配置成功不等于载具运行验证。");
        var targets=new JsonArray();for(String kind:new String[]{"coordinates","landmark","area","current_place"})targets.add(kind);out.add("accepted_target_kinds",targets);
        out.add("accepted_preferences",new JsonObject());out.add("accepted_hard_constraints",new JsonArray());
        var fields=new JsonObject();
        field(fields,"operation","string","inspect (default), set_speed, set_throttle, set_link_mode, set_frequency.");
        field(fields,"structure_id","string","Observed structure UUID; omit target. position is relative to origin_storage. Otherwise target is the world anchor.");
        field(fields,"position","object","Integer {x,y,z} component offset, default zero. Execution re-resolves the current pose and native hit region.");
        field(fields,"design_id","string","Optional saved world design for full post-configuration diff; omit with structure_id, which retains its own declarations.");
        field(fields,"value","integer","Required only for set_speed or set_throttle. Speed is the native signed dial value, nonzero -256..256; observed actual_rpm may differ with facing or drivetrain. Throttle is actual output signal 0..15, accounting for inverted levers.");
        field(fields,"receiver","boolean","Required only for set_link_mode: true receives, false transmits. Native wrench toggles only if actual mode differs.");
        field(fields,"frequency_items","array<string>","Required only for set_frequency: two ordered item IDs. Each slot uses a real carried/supplied stack and native right click; minecraft:air clears a slot. Actual item/color frequency identity is returned. No inventory or NBT injection.");
        out.add("parameters",fields);
        out.addProperty("execution_boundary","inspect is read-only. Settings are configured before departure; an explicit throttle operation may stop a moving structure. Missing confirmation never causes input replay. Native operation facts, current configuration and full registered-block diff are separate.");
        return out;
    }
    private static void field(JsonObject fields,String name,String type,String description) {
        var value=new JsonObject();value.addProperty("type",type);value.addProperty("description",description);fields.add(name,value);
    }
}
