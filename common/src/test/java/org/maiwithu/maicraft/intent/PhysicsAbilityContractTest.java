package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.physics.PhysicalBalanceTaskRecord;

/** 模型看到的能力契约必须能创建同一物理任务；默认查询不应携带任何隐式施工补丁。 */
public final class PhysicsAbilityContractTest {
    public static void run() throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        try(var world=new InteractionWorldTestHarness()) {
            var parameters=JsonParser.parseString("{\"structure_id\":\"00000000-0000-4000-8000-000000000001\"}").getAsJsonObject();
            var goal=goal(parameters);SemanticGoalContract.validate(goal,IntentRuntime.KNOWN_ABILITIES);
            var action=AbilityAdapter.adapt(goal,world.player,null);
            if(!(action instanceof IntentAction.Native nativeAction)||!(nativeAction.record() instanceof PhysicalBalanceTaskRecord record)
                    ||!record.parameters.operation().equals("analyze")) throw new AssertionError("默认物理目标没有创建只读分析任务");
            if(!SemanticAbilityCatalog.parameterNames(PhysicsAbilityAdapter.ABILITY).contains("edits")) throw new AssertionError("补丁契约未对模型公开");
            parameters.addProperty("operation","apply");
            try { SemanticGoalContract.validate(goal(parameters),IntentRuntime.KNOWN_ABILITIES);throw new AssertionError("无补丁施工被接单"); }
            catch(IllegalArgumentException expected) { }
            parameters.add("edits",JsonParser.parseString("[{\"position\":{\"x\":0,\"y\":-1,\"z\":0},\"block_id\":\"minecraft:iron_block\"}]"));
            SemanticGoalContract.validate(goal(parameters),IntentRuntime.KNOWN_ABILITIES);
        }
    }
    private static Goal goal(JsonObject parameters) {
        return new Goal(PhysicsAbilityAdapter.ABILITY,"起飞前检查并配平",null,parameters.toString(),"{}",List.of(),List.of());
    }
}
