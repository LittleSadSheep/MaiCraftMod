package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.physics.PhysicalAssemblerTaskRecord;
import org.maiwithu.maicraft.core.task.physics.PhysicalBondTaskRecord;

/** 能力发现、严格参数校验与真实任务适配必须一致，不能让默认观察隐式变成粘接或切换拉杆。 */
public final class PhysicalAssemblyContractTest {
    public static void run() throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var target=new Goal.SemanticTarget("coordinates",null,new Goal.WorldPosition(4,1,4,"minecraft:overworld"),null);
        try(var world=new InteractionWorldTestHarness()) {
            var inspect=goal(target,new JsonObject());SemanticGoalContract.validate(inspect,IntentRuntime.KNOWN_ABILITIES);
            var read=AbilityAdapter.adapt(inspect,world.player,null);
            check(read instanceof IntentAction.Native nativeAction&&nativeAction.record() instanceof PhysicalAssemblerTaskRecord,"观察能力没有创建原生观察任务");
            var bond=JsonParser.parseString("{\"operation\":\"bond\",\"adhesive\":\"simulated:honey_glue\",\"first\":{\"x\":0,\"y\":0,\"z\":0},\"second\":{\"x\":2,\"y\":1,\"z\":2}}").getAsJsonObject();
            var selected=goal(target,bond);SemanticGoalContract.validate(selected,IntentRuntime.KNOWN_ABILITIES);
            check(AbilityAdapter.adapt(selected,world.player,null) instanceof IntentAction.Native nativeAction
                    &&nativeAction.record() instanceof PhysicalBondTaskRecord,"粘接参数没有创建对应原生任务");
            check(SemanticAbilityCatalog.parameterNames(PhysicalAssemblyAbilityAdapter.ABILITY).containsAll(List.of("adhesive","design_id","project_id","declarations")),"完整设计和胶种契约未公开");
            var body=JsonParser.parseString("{\"operation\":\"disassemble\",\"structure_id\":\"00000000-0000-4000-8000-000000000001\"}").getAsJsonObject();
            rejects(goal(target,body));SemanticGoalContract.validate(goal(null,body),IntentRuntime.KNOWN_ABILITIES);
            rejects(goal(null,new JsonObject()));
            bond.remove("second");rejects(goal(target,bond));
        }
    }
    private static Goal goal(Goal.SemanticTarget target,JsonObject parameters) {
        return new Goal(PhysicalAssemblyAbilityAdapter.ABILITY,"原生粘接与组装",target,parameters.toString(),"{}",List.of(),List.of());
    }
    private static void rejects(Goal goal) {
        try {SemanticGoalContract.validate(goal,IntentRuntime.KNOWN_ABILITIES);}catch(IllegalArgumentException expected){return;}
        throw new AssertionError("含糊或混合坐标的组装目标被接受");
    }
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
}
