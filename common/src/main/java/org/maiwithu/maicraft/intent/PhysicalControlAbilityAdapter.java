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
        // 能力列表也公开航空配置入口，模型无需先猜操作名才能发现供气旋钮和螺旋桨成型流程。
        var out=new JsonObject();out.addProperty("summary","起飞/行驶前配置物理部件：电机转速、油门信号、无线收发频率、热气燃烧器容量，以及螺旋桨轴承组装/减速拆回。使用真实走位、命中、物品与原生协议，配置结果和载具运行验证分别返回。");
        var targets=new JsonArray();for(String kind:new String[]{"coordinates","landmark","area","current_place"})targets.add(kind);out.add("accepted_target_kinds",targets);
        out.add("accepted_preferences",new JsonObject());out.add("accepted_hard_constraints",new JsonArray());
        var fields=new JsonObject();
        field(fields,"operation","string","inspect (default), set_speed, set_throttle, set_link_mode, set_frequency, set_burner_volume, assemble_propeller, disassemble_propeller. Propeller actions use an empty-hand native click only when the requested state differs; input confirmation is separate from actual assembly/slowdown/disassembly and errors.");
        field(fields,"structure_id","string","Observed structure UUID; omit target. position is relative to origin_storage. Otherwise target is the world anchor.");
        field(fields,"position","object","Integer {x,y,z} component offset, default zero. Execution re-resolves the current pose and native hit region.");
        field(fields,"design_id","string","Optional saved world design for full post-configuration diff; omit with structure_id, which retains its own declarations.");
        // 起飞前先设置供气容量，再通过实际红石控制启停；面板容量本身不证明气球已有升力。
        field(fields,"value","integer","Required for set_speed, set_throttle or set_burner_volume. Speed: native signed dial, nonzero -256..256; actual_rpm may differ with facing. Throttle: actual output signal 0..15. Burner: hot-air volume at full signal, at least 5 and within the observed native maximum; rounded down to the native volume_step with a minimum of 5. Actual volume_setting, signal and gas_output are returned; this does not power the burner.");
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
