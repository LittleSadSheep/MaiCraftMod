// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
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

/** Regional endpoint evidence is supplied explicitly; no Create physics or world mutations are simulated. */
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
            var tick = CreateMechanicalPowerTask.class.getDeclaredMethod("progressSurvey", org.maiwithu.maicraft.client.actor.LocalPlayerContext.class); tick.setAccessible(true);
            check(tick.invoke(task, ClientRuntime.requireContext(h.player)) == TaskState.RUNNING, "resolved endpoint evidence must hand off before any old route execution");
            var economic = (EconomicKineticTaskRecord) field(CreateMechanicalPowerTask.class, "economicRecord").get(task);
            check(economic.source.equals(source.position()) && economic.target.equals(target.position()), "economic routing receives actual resolved machines, not empty region centers");
            check(economic.sourceFace == null && economic.targetFace == null, "unconstrained regions retain economic comparison of native faces");
            check(economic.materialPolicy == MaterialPolicy.INVENTORY_ONLY && economic.allowedSources.equals(List.of(Source.STORAGE))
                    && !economic.allowHarm && economic.protectedLabels.equals(List.of("home")), "handoff preserves all supply and protection policy");
            check(field(CreateMechanicalPowerTask.class, "plan").get(task) == null, "AUTO must not construct an encased-chain plan after semantic resolution");
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
