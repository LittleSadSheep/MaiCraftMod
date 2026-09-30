// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import java.util.List;
import java.util.Comparator;
import java.util.LinkedHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
        return resolved(request, dimension, source, target, 0);
    }
    static EconomicKineticTaskRecord resolved(CreateMechanicalPowerTaskRecord request, String dimension,
            CreateMechanicalPlan.KineticEndpoint source, CreateMechanicalPlan.KineticEndpoint target, int attempt) {
        // 续接和调查交接也复核类型，防止缓存候选绕过入口的筛选。
        if(!request.request.source().accepts(source.state()) || !request.request.destination().accepts(target.state()))
            throw new IllegalArgumentException("mechanical_endpoint_type_mismatch");
        return new EconomicKineticTaskRecord(request.getToolCallId() + "-economical-" + attempt, request.getDeadlineGameTime(), dimension,
                request.request.source().name(), source.position(), request.request.source().exactFace(),
                request.request.destination().name(), target.position(), request.request.destination().exactFace(),
                BuiltInRegistries.BLOCK.getKey(target.state().getBlock()).toString(), 0, 64, false,
                request.materialPolicy, request.protectedLabels, request.allowedSources, request.allowHarm,
                request.request.transmission() == CreateMechanicalPower.Transmission.CHAIN_CONVEYOR);
    }

    static List<CreateMechanicalPlan.KineticEndpoint> equivalentDestinations(Level world, CreateMechanicalPower.Request request,
            CreateMechanicalPlan.KineticEndpoint source, List<CreateMechanicalPlan.KineticEndpoint> candidates) {
        var first = candidates.getFirst();
        if (request.destination().exactFace() != null) return List.of(first);
        BlockPos controller = CreateBeltAccess.controller(world, first.position());
        if (controller == null) return List.of(first);
        var unique = new LinkedHashMap<BlockPos, CreateMechanicalPlan.KineticEndpoint>();
        // 用户点名的接收结构不变，只在同一皮带控制器的已知轴口里优先尝试靠近来源的接口。
        // 同一位置的不同面由经济规划器重新枚举，避免把它们当成多个目标重复报价。
        for (var candidate : candidates) if (request.destination().accepts(candidate.state())
                && controller.equals(CreateBeltAccess.controller(world, candidate.position())))
            unique.putIfAbsent(candidate.position(), candidate);
        // 上游最近证据筛选会删掉同一条带的较远轴口；从已确定的原生带链读取它们，避免“候选换口”实际永远只有一个。
        for (BlockPos at : CreateBeltAccess.loadedMembers(world, first.position())) {
            var state = world.getBlockState(at);
            if (!request.destination().accepts(state) || unique.containsKey(at)) continue;
            var facts = CreateKineticsBridge.inspect(world, at);
            if (facts == null) continue;
            for (Direction face : Direction.values()) if (CreateKineticsBridge.hasShaftTowards(world, at, state, face)) {
                unique.put(at, new CreateMechanicalPlan.KineticEndpoint(at, state, face, facts.speed(), facts.hasNetwork()));
                break;
            }
        }
        if (unique.isEmpty()) return List.of(first);
        return unique.values().stream().sorted(Comparator.comparingDouble(candidate ->
                candidate.position().distSqr(source.position()))).toList();
    }
}
