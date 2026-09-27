// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.SemanticResultView;
import org.maiwithu.maicraft.task.TaskResult;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.integration.create.transmission.EconomicKineticTaskRecord;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;

/** 区域端点证据由测试显式提供；不模拟 Create 物理行为或世界修改。 */
public final class CreateEconomicEndpointBridgeTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            var request = CreateMechanicalPower.Request.preserving(new CreateMechanicalPower.Endpoint("city area", new BlockPos(2,1,2)),
                    new CreateMechanicalPower.Endpoint("machine depot", new BlockPos(10,1,2)));
            var dispatched = CreateMechanicalPower.task("regional-auto", 1000, request, MaterialPolicy.INVENTORY_ONLY, List.of(Source.STORAGE), false, List.of("home"));
            check(dispatched instanceof CreateMechanicalPowerTaskRecord, "air/non-kinetic centers require semantic endpoint resolution before Economic admission");
            var record = (CreateMechanicalPowerTaskRecord) dispatched;
            var source = new CreateMechanicalPlan.KineticEndpoint(new BlockPos(3,1,2), Blocks.OAK_LOG.defaultBlockState(), Direction.EAST, 16, true);
            var target = new CreateMechanicalPlan.KineticEndpoint(new BlockPos(9,1,2), Blocks.OAK_LOG.defaultBlockState(), Direction.WEST, 0, false);
            var survey = new CreateProgressiveSurvey(request, true);
            field(CreateProgressiveSurvey.class, "sourceCandidates").set(survey, List.of(source));
            field(CreateProgressiveSurvey.class, "destinationCandidates").set(survey, List.of(target));
            var phase = field(CreateProgressiveSurvey.class, "phase");
            for (Object value : phase.getType().getEnumConstants()) if (value.toString().equals("READY")) phase.set(survey, value);
            var task = new CreateMechanicalPowerTask(h.player, record);
            field(CreateMechanicalPowerTask.class, "progressiveSurvey").set(task, survey);
            field(CreateMechanicalPowerTask.class, "economicAfterEndpoints").setBoolean(task, true);
            field(CreateMechanicalPowerTask.class, "dimension").set(task, "minecraft:overworld");
            var tick = CreateMechanicalPowerTask.class.getDeclaredMethod("progressSurvey", LocalPlayerContext.class); tick.setAccessible(true);
            check(tick.invoke(task, ClientRuntime.requireContext(h.player)) == TaskState.RUNNING, "resolved endpoint evidence must hand off before any old route execution");
            var economic = (EconomicKineticTaskRecord) field(CreateMechanicalPowerTask.class, "economicRecord").get(task);
            check(economic.source.equals(source.position()) && economic.target.equals(target.position()), "economic routing receives actual resolved machines, not empty region centers");
            check(economic.sourceFace == null && economic.targetFace == null, "unconstrained regions retain economic comparison of native faces");
            check(economic.materialPolicy == MaterialPolicy.INVENTORY_ONLY && economic.allowedSources.equals(List.of(Source.STORAGE))
                    && !economic.allowHarm && economic.protectedLabels.equals(List.of("home")), "handoff preserves all supply and protection policy");
            check(field(CreateMechanicalPowerTask.class, "plan").get(task) == null, "AUTO must not construct an encased-chain plan after semantic resolution");
            // 明确锁链传动轮从语义锚点转成交给经济规划的实机端点后，仍必须携带技术约束。
            var chainRequest = new CreateMechanicalPower.Request(request.source(),request.destination(),
                    CreateMechanicalPower.Transmission.CHAIN_CONVEYOR,true,false);
            var chainRecord = (CreateMechanicalPowerTaskRecord) CreateMechanicalPower.task("regional-chain",1000,chainRequest);
            var chainEconomic = CreateEconomicEndpointBridge.resolved(chainRecord,"minecraft:overworld",source,target);
            check(chainEconomic.requireChainConveyor && !economic.requireChainConveyor,
                    "explicit chain-conveyor admission must not become ordinary AUTO or encased chain drive");
            // 尚未找到路也要公开选型含义，让语义调用方能识别锁链轮和链式传动箱的不同参数。
            var resultData = CreateMechanicalPowerTask.class.getDeclaredMethod("resultData"); resultData.setAccessible(true);
            var chainFacts = (Map<?,?>) resultData.invoke(new CreateMechanicalPowerTask(h.player,chainRecord));
            check(chainFacts.get("requested_transmission").equals("chain_conveyor")
                    && chainFacts.get("transmission_description").toString().contains("锁链传动轮"),"chain selection is visible before route success");
            var boxedRequest = new CreateMechanicalPower.Request(request.source(),request.destination(),
                    CreateMechanicalPower.Transmission.ENCASED_CHAIN_DRIVE,true,false);
            var boxedRecord = (CreateMechanicalPowerTaskRecord) CreateMechanicalPower.task("boxed",1000,boxedRequest);
            var boxedFacts = (Map<?,?>) resultData.invoke(new CreateMechanicalPowerTask(h.player,boxedRecord));
            check(boxedFacts.get("transmission_description").toString().contains("链式传动箱")
                    && ((List<?>)boxedFacts.get("available_transmission_choices")).contains("chain_conveyor"),"legacy family receipts retain the distinct available choice");
            // 接线失败先经过语义结果再经过注意流；技术名称不能在这两层再次被删掉。
            var compact = IntentRuntime.class.getDeclaredMethod("compactAttentionResult",JsonObject.class); compact.setAccessible(true);
            var report = TaskResult.fail("route unavailable",Map.of("requested_transmission",boxedFacts.get("requested_transmission"),
                    "transmission_description",boxedFacts.get("transmission_description"),"available_transmission_choices",boxedFacts.get("available_transmission_choices")));
            var notice = (JsonObject) compact.invoke(null,JsonParser.parseString(SemanticResultView.result(report).toJson()).getAsJsonObject());
            check(notice.getAsJsonObject("data").get("transmission_description").getAsString().contains("链式传动箱")
                    && notice.getAsJsonObject("data").getAsJsonArray("available_transmission_choices").size() == 3,
                    "attention exposes requested technology and its available alternatives");
            try {
                new CreateMechanicalPower.Request(request.source(),request.destination(),CreateMechanicalPower.Transmission.CHAIN_CONVEYOR,true,true);
                throw new AssertionError("unobserved chain receiver accepted");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().equals("chain_conveyor_requires_observed_receiver"),"unsupported receiver scope is explicit");
            }
            var legacy = CreateEndpointContinuations.issue(request, new CreateProgressiveSurvey(request), 1, "minecraft:overworld");
            check(!legacy.economicAfterEndpoints(), "legacy opaque endpoint continuations preserve their original executor");
            CreateEndpointContinuations.discard(legacy.token());
            var fresh = CreateEndpointContinuations.issue(request, new CreateProgressiveSurvey(request, true), 1, "minecraft:overworld", true);
            check(CreateEndpointContinuations.take(fresh.token()).economicAfterEndpoints() && CreateEndpointContinuations.take(fresh.token()) == null,
                    "fresh AUTO exploration continuation retains economical handoff and remains single-use");
            var resume = CreateMechanicalPower.resumeTask("legacy-resume", 1000, request, UUID.randomUUID());
            check(resume instanceof CreateMechanicalPowerTaskRecord && ((CreateMechanicalPowerTaskRecord) resume).continuationToken != null,
                    "opaque resume never becomes an unbound fresh economic task");
            check(h.blockUses()==0 && h.itemUses()==0, "semantic resolution and handoff cannot place or consume anything");
        }
        System.out.println("CreateEconomicEndpointBridgeTest: regional anchors, concrete economical handoff, policy and legacy continuation passed");
    }
    private static Field field(Class<?> owner,String name) throws Exception {var field=owner.getDeclaredField(name);field.setAccessible(true);return field;}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
