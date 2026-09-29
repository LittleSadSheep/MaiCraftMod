// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.SemanticResultView;
import org.maiwithu.maicraft.task.TaskResult;

public final class KineticRouteContinuationTest {
    public static void main(String[] args)throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        try(var h=new InteractionWorldTestHarness()) {
            h.player.setUUID(UUID.randomUUID());var a=new BlockPos(1,1,1);var b=new BlockPos(5,1,1);var cell=new BlockPos(3,1,1);
            var supplyProgress=new KineticSupplyProgress();
            h.inventory.setItem(0,new ItemStack(Items.STONE,4));
            check(supplyProgress.begin(h.inventory),"first missing-material inventory is admitted");
            h.inventory.setItem(0,new ItemStack(Items.COBBLESTONE,4));
            check(supplyProgress.begin(h.inventory),"changed stock can advance another material demand");
            h.inventory.setItem(0,new ItemStack(Items.STONE,4));
            check(!supplyProgress.begin(h.inventory),"reversible conversions must not endlessly consume each other's reserved final materials");
            var source=new KineticRouteGeometry.Endpoint(a,Direction.Axis.X,List.of(Direction.EAST),"shaft");
            var target=new KineticRouteGeometry.Endpoint(b,Direction.Axis.X,List.of(Direction.WEST),"shaft");
            var plan=new KineticRouteGeometry.Plan("axial_shaft",source,Direction.EAST,target,Direction.WEST,
                    List.of(new KineticRouteGeometry.Placement(cell,"minecraft:stone",Map.of())),List.of(),Map.of("minecraft:stone",1));
            var request=new EconomicKineticTaskRecord("test",1000,"minecraft:overworld","city",a,Direction.EAST,"input",b,
                    Direction.WEST,"minecraft:stone",0,64,false,MaterialPolicy.INVENTORY_ONLY,List.of());
            KineticRouteContinuations.retain(h.player,request,plan,new JsonObject());
            // 同一对端点由自动选型改成锁链传动轮时，旧的轴线路由不能被静默续建为新要求的成果。
            var chainOnly = new EconomicKineticTaskRecord("chain",1000,"minecraft:overworld","city",a,Direction.EAST,"input",b,
                    Direction.WEST,"minecraft:stone",0,64,false,MaterialPolicy.INVENTORY_ONLY,List.of(),List.of(),false,true);
            check(!chainOnly.accepts(plan) && request.accepts(plan),"explicit chain constraint excludes shaft-only candidates");
            var chainPlan = new KineticRouteGeometry.Plan("chain_conveyor",source,Direction.EAST,target,Direction.WEST,
                    List.of(),List.of(new KineticRouteGeometry.ChainLink(a,b,4)),Map.of("minecraft:chain",4));
            check(chainOnly.accepts(chainPlan),"a declared native chain link satisfies the candidate-family filter");
            try { KineticRouteContinuations.find(h.player,chainOnly); throw new AssertionError("different transmission reused old partial route"); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().contains("partial_route"),"changed technology keeps the original partial-route boundary"); }
            check(KineticRouteContinuations.find(h.player,request).plan()==plan,"an unfinished request must retain its exact selected plan");
            check(KineticRouteContinuations.remaining(h.player,plan).equals(Map.of("minecraft:stone",1)),"an absent cell still needs its material");
            h.set(cell,Blocks.STONE.defaultBlockState());
            check(KineticRouteContinuations.remaining(h.player,plan).isEmpty(),"confirmed existing cells cannot demand or consume their materials again");
            check(KineticRouteContinuations.find(h.player,request)!=null,"matching construction must be resumable");
            h.set(cell,Blocks.COBBLESTONE.defaultBlockState());
            try {KineticRouteContinuations.find(h.player,request);throw new AssertionError("replaced cell accepted");}
            catch(IllegalArgumentException expected){check(expected.getMessage().contains("cell_changed"),"a changed partial route requires inspection");}
            KineticRouteContinuations.completed(h.player,b);check(KineticRouteContinuations.find(h.player,request)==null,"completion releases bounded ownership");
            var observed=new KineticNativeView.Observation(source,"create:shaft",16,true,"network-a",true,false);
            var evidence=KineticPowerEvidence.client(observed);
            check(KineticNativeReads.text(evidence,"provenance").equals("client_synchronized_native"),"client observations must not claim server proof");
            check(KineticPowerEvidence.sameNetwork(observed,evidence),"the same synchronized network can be checked without a return trip");
            check(!KineticPowerEvidence.sameNetwork(new KineticNativeView.Observation(source,"create:shaft",16,true,"network-b",true,false),evidence),"source reassignment invalidates an old network proof");
            independentEndpointEvidence(h, request, observed);
            check(h.blockUses()==0&&h.itemUses()==0,"ledger reconciliation and evidence checks never mutate the world");
        }
        System.out.println("KineticRouteContinuationTest: exact partial ownership, remaining materials and labeled network evidence passed");
    }

    private static void independentEndpointEvidence(InteractionWorldTestHarness h, EconomicKineticTaskRecord request,
                                                     KineticNativeView.Observation running) throws Exception {
        var task = new EconomicKineticTask(h.player, request);
        var unknown = task.resultData();
        check(!unknown.containsKey("source_power_observed") && !unknown.containsKey("destination_power_observed"),
                "unobserved endpoints must not be reported as stopped");
        check(((JsonObject) unknown.get("source_power_evidence")).get("observation_status").getAsString().equals("not_observed"),
                "absence of native evidence is explicit");
        // 有电的源与停转的目标独立保留；接线尚未完成时，模型仍能知道需要查的是哪一端。
        var stopped = new KineticNativeView.Observation(running.endpoint(),"create:shaft",0,false,"",false,false);
        var before = EconomicKineticTask.class.getDeclaredField("sourceBefore"); before.setAccessible(true);
        var destination = EconomicKineticTask.class.getDeclaredField("targetBefore"); destination.setAccessible(true);
        before.set(task,KineticPowerEvidence.client(running)); destination.set(task,KineticPowerEvidence.client(stopped));
        var result = task.resultData();
        check(Boolean.TRUE.equals(result.get("source_power_observed")) && Boolean.FALSE.equals(result.get("destination_power_observed"))
                && Boolean.FALSE.equals(result.get("power_ready")), "endpoint power is independent from complete route acceptance");
        check(!((JsonObject)result.get("destination_power_evidence")).get("overstressed").getAsBoolean(),
                "zero rpm without a network is not an observed overload");
        check(result.get("source_native_observation_stage").equals("before_construction")
                && result.get("target_native_observation_stage").equals("before_construction"), "before snapshots are not labeled after construction");
        // 后读到停转就覆盖旧的有电观察；失败回执不能靠沿用先前转速把当前状态升级为通过。
        var after = EconomicKineticTask.class.getDeclaredField("sourceAfter"); after.setAccessible(true);
        after.set(task,KineticPowerEvidence.client(stopped));
        check(Boolean.FALSE.equals(task.resultData().get("source_power_observed")), "latest endpoint read wins");
        var partial = new LinkedHashMap<String,Object>();
        KineticPowerEvidence.append(partial,"source",new JsonObject(),64);
        check(!partial.containsKey("source_power_observed"), "incomplete native rows cannot invent a power verdict");
        KineticPowerEvidence.append(partial,"source",KineticPowerEvidence.client(running),64);
        check(!((JsonObject)partial.get("source_power_evidence")).get("minimum_rpm_met").getAsBoolean()
                && Boolean.TRUE.equals(partial.get("source_power_observed")), "rotating below requested rpm is reported separately");
        // 注意流保留这组独立观察，等待任务通知的模型也能分清未接通、目标停转与来源有电。
        var compact = IntentRuntime.class.getDeclaredMethod("compactAttentionResult",JsonObject.class); compact.setAccessible(true);
        var notice = (JsonObject)compact.invoke(null,JsonParser.parseString(SemanticResultView.result(
                TaskResult.fail("fixture route incomplete",result)).toJson()).getAsJsonObject());
        check(notice.getAsJsonObject("data").getAsJsonObject("source_power_evidence").get("powered").getAsBoolean(),
                "attention retains observed source rotation");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
