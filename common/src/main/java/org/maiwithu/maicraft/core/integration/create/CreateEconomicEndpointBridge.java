// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.core.integration.create.transmission.EconomicKineticTaskRecord;

/** 先解析语义锚点，再将具体且新鲜观察到的机器交给经济路线规划。 */
final class CreateEconomicEndpointBridge {
    private CreateEconomicEndpointBridge() {}
    static boolean direct(Level world, CreateMechanicalPower.Request request) {
        var source = CreateKineticsBridge.inspect(world, request.source().center());
        // 锚点本身虽有转动部件，也必须符合点名类型；否则交给同类型端点调查，不能直接接错机器。
        return source != null && source.powered() && CreateKineticsBridge.inspect(world, request.destination().center()) != null
                && request.source().accepts(world.getBlockState(request.source().center()))
                && request.destination().accepts(world.getBlockState(request.destination().center()));
    }
    static EconomicKineticTaskRecord resolved(CreateMechanicalPowerTaskRecord request, String dimension,
            CreateMechanicalPlan.KineticEndpoint source, CreateMechanicalPlan.KineticEndpoint target) {
        // 续接和调查交接也复核类型，防止缓存候选绕过入口的筛选。
        if(!request.request.source().accepts(source.state()) || !request.request.destination().accepts(target.state()))
            throw new IllegalArgumentException("mechanical_endpoint_type_mismatch");
        return new EconomicKineticTaskRecord(request.getToolCallId() + "-economical", request.getDeadlineGameTime(), dimension,
                request.request.source().name(), source.position(), request.request.source().exactFace(),
                request.request.destination().name(), target.position(), request.request.destination().exactFace(),
                BuiltInRegistries.BLOCK.getKey(target.state().getBlock()).toString(), 0, 64, false,
                request.materialPolicy, request.protectedLabels, request.allowedSources, request.allowHarm,
                request.request.transmission() == CreateMechanicalPower.Transmission.CHAIN_CONVEYOR);
    }
}
