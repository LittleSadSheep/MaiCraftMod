package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.physics.PhysicalControlParameters;
import org.maiwithu.maicraft.core.task.physics.PhysicalControlTaskRecord;

/** 模型只声明部件设置；坐标身份、频率顺序与面板范围必须在接管身体前明确。 */
public final class PhysicalControlContractTest {
    public static void run() throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var worldTarget=new Goal.SemanticTarget("coordinates",null,new Goal.WorldPosition(4,1,4,"minecraft:overworld"),null);
        try(var h=new InteractionWorldTestHarness()) {
            var inspect=goal(worldTarget,new JsonObject());SemanticGoalContract.validate(inspect,IntentRuntime.KNOWN_ABILITIES);
            check(AbilityAdapter.adapt(inspect,h.player,null) instanceof IntentAction.Native action
                    &&action.record() instanceof PhysicalControlTaskRecord,"默认观察没有进入物理部件任务");
            var frequency=json("{\"operation\":\"set_frequency\",\"frequency_items\":[\"minecraft:iron_ingot\",\"minecraft:redstone\"]}");
            SemanticGoalContract.validate(goal(worldTarget,frequency),IntentRuntime.KNOWN_ABILITIES);
            check(PhysicalControlParameters.parse(frequency).frequencyItems().equals(List.of("minecraft:iron_ingot","minecraft:redstone")),"无线频率顺序被改写");
            // 轮胎配置必须明确安装种类或空手拆除；不能把物品字段附到普通旋钮操作上。
            for(String item:List.of("offroad:small_tire","minecraft:air")) {
                var tire=json("{\"operation\":\"set_tire\",\"item_id\":\""+item+"\"}");
                SemanticGoalContract.validate(goal(worldTarget,tire),IntentRuntime.KNOWN_ABILITIES);
                check(PhysicalControlParameters.parse(tire).itemId().equals(item),"明确轮胎种类在解析中丢失");
            }
            rejects(goal(worldTarget,json("{\"operation\":\"set_tire\"}")));
            rejects(goal(worldTarget,json("{\"operation\":\"set_tire\",\"item_id\":false}")));
            check(PhysicalControlParameters.parse(json("{\"operation\":\"set_tire\",\"item_id\":\"air\"}")).itemId().equals("minecraft:air"),"省略默认命名空间的空手拆胎仍应幂等");
            rejects(goal(worldTarget,json("{\"operation\":\"inspect\",\"item_id\":\"minecraft:air\"}")));
            check(SemanticAbilityCatalog.parameterNames(PhysicalControlAbilityAdapter.ABILITY).contains("item_id"),"轮胎配置入口未公开");
            // 配键只改一个键，按键必须有限保持；越权字段、重复键和退出键在原生接管前明确拒绝。
            SemanticGoalContract.validate(goal(worldTarget,json("{\"operation\":\"bind_typewriter_key\",\"key\":\"a\",\"frequency_items\":[\"minecraft:iron_ingot\",\"minecraft:redstone\"]}")),IntentRuntime.KNOWN_ABILITIES);
            SemanticGoalContract.validate(goal(worldTarget,json("{\"operation\":\"press_typewriter_keys\",\"keys\":[\"w\",\"left\"],\"duration_seconds\":0.5}")),IntentRuntime.KNOWN_ABILITIES);
            // 舵角观察与按键使用相同坐标系，观察不会升级成第二组操作指令。
            SemanticGoalContract.validate(goal(worldTarget,json("{\"operation\":\"press_typewriter_keys\",\"keys\":[\"a\"],\"observe_positions\":[{\"x\":0,\"y\":1,\"z\":2}]}")),IntentRuntime.KNOWN_ABILITIES);
            for(String invalid:List.of("{\"operation\":\"inspect\",\"keys\":[\"a\"]}",
                    "{\"operation\":\"bind_typewriter_key\",\"key\":\"a\"}",
                    "{\"operation\":\"press_typewriter_keys\",\"keys\":[]}",
                    "{\"operation\":\"press_typewriter_keys\",\"keys\":[\"a\",\"A\"]}",
                    "{\"operation\":\"press_typewriter_keys\",\"keys\":[\"escape\"]}",
                    "{\"operation\":\"press_typewriter_keys\",\"keys\":[\"w\"],\"duration_seconds\":0}",
                    "{\"operation\":\"press_typewriter_keys\",\"keys\":[\"w\"],\"duration_seconds\":31}"))rejects(goal(worldTarget,json(invalid)));
            check(SemanticAbilityCatalog.parameterNames(PhysicalControlAbilityAdapter.ABILITY).containsAll(List.of("key","keys")),"打字机配键和操作入口未公开");
            // 供气容量是明确的配置意图，不能误按油门十五档限制，也不接受低于原生最小值的零供气旋钮。
            SemanticGoalContract.validate(goal(worldTarget,json("{\"operation\":\"set_burner_volume\",\"value\":125}")),IntentRuntime.KNOWN_ABILITIES);
            rejects(goal(worldTarget,json("{\"operation\":\"set_burner_volume\",\"value\":0}")));
            // 限角用角度范围，不能把正舵角当成油门信号，也不能通过零度伪造弹簧停机。
            for(int angle:List.of(1,20,360)) SemanticGoalContract.validate(
                    goal(worldTarget,json("{\"operation\":\"set_spring_angle\",\"value\":"+angle+"}")),IntentRuntime.KNOWN_ABILITIES);
            for(int angle:List.of(0,-20,361)) rejects(goal(worldTarget,
                    json("{\"operation\":\"set_spring_angle\",\"value\":"+angle+"}")));
            // 成型与拆回是明确的幂等目标，不接受含糊的开关值，也不要求模型发送点击脚本。
            for(String operation:List.of("assemble_propeller","disassemble_propeller")) {
                SemanticGoalContract.validate(goal(worldTarget,json("{\"operation\":\""+operation+"\"}")),IntentRuntime.KNOWN_ABILITIES);
                rejects(goal(worldTarget,json("{\"operation\":\""+operation+"\",\"value\":1}")));
            }
            // 移动船体上的手摇沿用普通手摇时长边界；不到一刻的明确时长仍保留一刻，其他操作不接受持续重放。
            var crank=json("{\"operation\":\"turn_crank\",\"duration_seconds\":0.01}");
            SemanticGoalContract.validate(goal(worldTarget,crank),IntentRuntime.KNOWN_ABILITIES);
            check(PhysicalControlParameters.parse(crank).crankTicks()==1,"手摇正时长不能被舍掉成无时长");
            check(PhysicalControlParameters.parse(json("{\"operation\":\"turn_crank\"}")).crankTicks()==0,"默认应只激活一次");
            rejects(goal(worldTarget,json("{\"operation\":\"turn_crank\",\"duration_seconds\":31}")));
            rejects(goal(worldTarget,json("{\"operation\":\"inspect\",\"duration_seconds\":1}")));
            check(SemanticAbilityCatalog.parameterNames(PhysicalControlAbilityAdapter.ABILITY).containsAll(List.of("value","receiver","frequency_items","structure_id")),"控制能力契约未对模型公开");
            for(String invalid:List.of("{\"operation\":\"set_speed\",\"value\":0}","{\"operation\":\"set_speed\",\"value\":1.5}",
                    "{\"operation\":\"set_throttle\",\"value\":16}","{\"operation\":\"set_link_mode\",\"receiver\":\"true\"}",
                    "{\"operation\":\"set_frequency\",\"frequency_items\":[\"minecraft:iron_ingot\"]}","{\"operation\":\"inspect\",\"value\":1}",
                    "{\"operation\":\"inspect\",\"nbt\":{}}"))rejects(goal(worldTarget,json(invalid)));
            var ship=json("{\"structure_id\":\"00000000-0000-4000-8000-000000000001\",\"position\":{\"x\":1,\"y\":0,\"z\":-1}}");
            rejects(goal(worldTarget,ship));SemanticGoalContract.validate(goal(null,ship),IntentRuntime.KNOWN_ABILITIES);
            // 艇上操作必须绑定同一结构，字符串真假值不能悄悄取消起飞时的人船约束。
            ship.addProperty("require_onboard",true);SemanticGoalContract.validate(goal(null,ship),IntentRuntime.KNOWN_ABILITIES);
            check(PhysicalControlParameters.parse(ship).requireOnboard(),"艇上操作要求在解析中丢失");
            ship.addProperty("require_onboard","true");rejects(goal(null,ship));
            rejects(goal(worldTarget,json("{\"require_onboard\":true}")));
            rejects(goal(null,new JsonObject()));
        }
    }
    private static JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    private static Goal goal(Goal.SemanticTarget target,JsonObject p){return new Goal(PhysicalControlAbilityAdapter.ABILITY,"配置物理部件",target,p.toString(),"{}",List.of(),List.of());}
    private static void rejects(Goal goal){try{SemanticGoalContract.validate(goal,IntentRuntime.KNOWN_ABILITIES);}catch(IllegalArgumentException expected){return;}throw new AssertionError("含糊或非法配置被接单");}
    private static void check(boolean okay,String why){if(!okay)throw new AssertionError(why);}
}
