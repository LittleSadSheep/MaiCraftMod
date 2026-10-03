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
            check(SemanticAbilityCatalog.parameterNames(PhysicalControlAbilityAdapter.ABILITY).containsAll(List.of("value","receiver","frequency_items","structure_id")),"控制能力契约未对模型公开");
            for(String invalid:List.of("{\"operation\":\"set_speed\",\"value\":0}","{\"operation\":\"set_speed\",\"value\":1.5}",
                    "{\"operation\":\"set_throttle\",\"value\":16}","{\"operation\":\"set_link_mode\",\"receiver\":\"true\"}",
                    "{\"operation\":\"set_frequency\",\"frequency_items\":[\"minecraft:iron_ingot\"]}","{\"operation\":\"inspect\",\"value\":1}",
                    "{\"operation\":\"inspect\",\"nbt\":{}}"))rejects(goal(worldTarget,json(invalid)));
            var ship=json("{\"structure_id\":\"00000000-0000-4000-8000-000000000001\",\"position\":{\"x\":1,\"y\":0,\"z\":-1}}");
            rejects(goal(worldTarget,ship));SemanticGoalContract.validate(goal(null,ship),IntentRuntime.KNOWN_ABILITIES);
            rejects(goal(null,new JsonObject()));
        }
    }
    private static JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    private static Goal goal(Goal.SemanticTarget target,JsonObject p){return new Goal(PhysicalControlAbilityAdapter.ABILITY,"配置物理部件",target,p.toString(),"{}",List.of(),List.of());}
    private static void rejects(Goal goal){try{SemanticGoalContract.validate(goal,IntentRuntime.KNOWN_ABILITIES);}catch(IllegalArgumentException expected){return;}throw new AssertionError("含糊或非法配置被接单");}
    private static void check(boolean okay,String why){if(!okay)throw new AssertionError(why);}
}
