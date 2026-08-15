// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.agent.tool.ToolRegistry;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.task.entity.GenericEntitySearchTaskRecord;

/** Registration and typed-record seam for generic semantic entity search. */
public final class SemanticEntitySearchApi {
    private static final long MIN_INITIAL_LEASE_TICKS = 3L * 60L * 20L;
    private static final long MAX_INITIAL_LEASE_TICKS = 20L * 60L * 20L;

    private SemanticEntitySearchApi() {}

    public static void register() {
        GenericEntitySearchTaskRecord.ensureRegistered();
        ToolRegistry.register(new SemanticEntitySearchTool());
    }

    public static GenericEntitySearchTaskRecord newRecord(
            ToolContext context,
            List<String> entityTypeIds,
            String relation,
            Integer count,
            Integer maxDistance,
            Boolean mayAlterTerrain,
            List<String> protectedLabels) {
        return newRecord(context, entityTypeIds, relation, count, maxDistance,
                mayAlterTerrain, protectedLabels, false);
    }

    /** Internal overload: acquisition may request the stricter pre-harm protection boundary. */
    public static GenericEntitySearchTaskRecord newRecord(
            ToolContext context,
            List<String> entityTypeIds,
            String relation,
            Integer count,
            Integer maxDistance,
            Boolean mayAlterTerrain,
            List<String> protectedLabels,
            boolean harmIntent) {
        List<ResourceLocation> types = new ArrayList<>();
        for (String raw : entityTypeIds == null ? List.<String>of() : entityTypeIds) {
            ResourceLocation id = ResourceLocation.tryParse(raw);
            if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                throw new IllegalArgumentException("unknown entity type: " + raw);
            }
            types.add(id);
        }
        int boundedCount = Math.clamp(count == null ? 1 : count,
                1, GenericEntitySearchTaskRecord.MAX_COUNT);
        int distance = Math.clamp(
                maxDistance == null ? GenericEntitySearchTaskRecord.DEFAULT_DISTANCE : maxDistance,
                GenericEntitySearchTaskRecord.MIN_DISTANCE,
                GenericEntitySearchTaskRecord.MAX_DISTANCE);
        long initialLease = Math.clamp(60L * 20L + distance * 16L,
                MIN_INITIAL_LEASE_TICKS, MAX_INITIAL_LEASE_TICKS);
        return new GenericEntitySearchTaskRecord(
                context.toolCallId(), context.deadline(initialLease), types,
                GenericEntitySearchTaskRecord.Relation.parse(relation),
                boundedCount, distance, Boolean.TRUE.equals(mayAlterTerrain),
                protectedLabels, harmIntent);
    }
}
