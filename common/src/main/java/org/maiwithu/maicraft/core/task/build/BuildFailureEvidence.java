// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Bounded model-level failure evidence survives semantic filtering without exposing route or input handles. */
final class BuildFailureEvidence {
    private BuildFailureEvidence() {}

    static Map<String, Object> describe(String code, BlockPos at, List<BuildTaskRecord.Target> targets,
                                       Predicate<BlockPos> loaded, Function<BlockPos, BlockState> read) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        int index = -1;
        for (int i = 0; i < targets.size(); i++) if (targets.get(i).pos().equals(at)) { index = i; break; }
        result.put("target_index", index);
        result.put("target_index_basis", "zero_based_frozen_project_order");
        result.put("declared_target", index >= 0);
        if (index >= 0) {
            var target = targets.get(index);
            result.put("expected", state(target.desiredState()));
            result.put("authored_properties", target.exactProperties().stream().sorted().toList());
            if (target.finalProperties() != null) result.put("important_properties", target.finalProperties().stream().sorted().toList());
        }
        boolean available = at != null && loaded.test(at);
        result.put("observed_loaded", available);
        if (available) result.put("observed", state(read.apply(at)));
        return Map.copyOf(result);
    }

    private static Map<String, Object> state(BlockState state) {
        Map<String, String> attributes = new LinkedHashMap<>();
        state.getValues().forEach((property, value) -> attributes.put(property.getName(), name(property, value)));
        return Map.of("block_id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), "attributes", attributes);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String name(Property property, Comparable value) { return property.getName(value); }
}
