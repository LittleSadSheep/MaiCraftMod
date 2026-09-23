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
        return source != null && source.powered() && CreateKineticsBridge.inspect(world, request.destination().center()) != null;
    }
    static EconomicKineticTaskRecord resolved(CreateMechanicalPowerTaskRecord request, String dimension,
            CreateMechanicalPlan.KineticEndpoint source, CreateMechanicalPlan.KineticEndpoint target) {
        return new EconomicKineticTaskRecord(request.getToolCallId() + "-economical", request.getDeadlineGameTime(), dimension,
                request.request.source().name(), source.position(), request.request.source().exactFace(),
                request.request.destination().name(), target.position(), request.request.destination().exactFace(),
                BuiltInRegistries.BLOCK.getKey(target.state().getBlock()).toString(), 0, 64, false,
                request.materialPolicy, request.protectedLabels, request.allowedSources, request.allowHarm);
    }
}
