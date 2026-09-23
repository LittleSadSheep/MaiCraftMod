// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.maiwithu.maicraft.core.integration.create.CreateProcessingCapabilities;

/** 冻结作者选择的加工点和承载点；施工后按真实状态重新核对，不用产品名称替代原生接口。 */
public record MachineProcessingRelation(BlockPos processor, BlockPos surface, List<BlockPos> clearance) {
    public MachineProcessingRelation { processor = processor.immutable(); surface = surface.immutable(); clearance = List.copyOf(clearance); }
    public static MachineProcessingRelation compile(BlockPos processor, BlockPos surface, Map<BlockPos, BlockState> states) {
        var source = states.get(processor); var destination = states.get(surface);
        if (source == null || destination == null) throw new IllegalArgumentException("processing_endpoint_missing");
        var registry = MachineConstructionPlan.registry();
        var action = registry.processing(id(source), properties(source)); var carrier = registry.processing(id(destination), properties(destination));
        if (!action.processor() || !carrier.surface() || !processor.offset(action.workOffset()).equals(surface))
            throw new IllegalArgumentException("native_processing_endpoints_incompatible");
        return new MachineProcessingRelation(processor, surface, action.clearance().stream().map(processor::offset).toList());
    }
    public boolean matches(Level world) {
        if (!world.isLoaded(processor) || !world.isLoaded(surface)) return false;
        var registry = MachineConstructionPlan.registry(); BlockState state = world.getBlockState(processor);
        var action = registry.processing(id(state), properties(state));
        var held = world.getBlockState(surface); var carrier = registry.processing(id(held), properties(held));
        return action.processor() && carrier.surface() && processor.offset(action.workOffset()).equals(surface)
                && action.requiredState().entrySet().stream().allMatch(entry -> entry.getValue().equals(properties(state).get(entry.getKey())))
                && clearance.stream().allMatch(at -> world.isLoaded(at) && CreateProcessingCapabilities.openProcessingSpace(world.getBlockState(at), world, at));
    }
    private static String id(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(); }
    private static Map<String, String> properties(BlockState state) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Property<?> property : state.getProperties()) result.put(property.getName(), value(state, property));
        return result;
    }
    private static <T extends Comparable<T>> String value(BlockState state, Property<T> property) { return property.getName(state.getValue(property)); }
}
