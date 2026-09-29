// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import org.maiwithu.maicraft.agent.tool.ToolRegistry;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.task.locate.SemanticBlockSearchTaskRecord;

/** 语义方块发现的注册入口和类型化任务记录接口。 */
public final class SemanticBlockSearchApi {
    private static final long MIN_INITIAL_LEASE_TICKS = 3L * 60L * 20L;
    private static final long MAX_INITIAL_LEASE_TICKS = 20L * 60L * 20L;

    private SemanticBlockSearchApi() {}

    public static void register() {
        SemanticBlockSearchTaskRecord.ensureRegistered();
        ToolRegistry.register(new SemanticBlockSearchTool());
    }

    public static SemanticBlockSearchTaskRecord newRecord(
            ToolContext context,
            List<String> blockIds,
            Integer count,
            Integer maxDistance) {
        List<Block> targets = new ArrayList<>();
        for (String raw : blockIds == null ? List.<String>of() : blockIds) {
            ResourceLocation id = ResourceLocation.tryParse(raw);
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                throw new IllegalArgumentException("unknown block id: " + raw);
            }
            targets.add(BuiltInRegistries.BLOCK.get(id));
        }
        int boundedCount = Math.clamp(count == null ? 1 : count,
                1, SemanticBlockSearchTaskRecord.MAX_COUNT);
        int distance = Math.clamp(
                maxDistance == null ? SemanticBlockSearchTaskRecord.DEFAULT_DISTANCE : maxDistance,
                SemanticBlockSearchTaskRecord.MIN_DISTANCE,
                SemanticBlockSearchTaskRecord.MAX_DISTANCE);
        long initialLease = Math.clamp(60L * 20L + distance * 16L,
                MIN_INITIAL_LEASE_TICKS, MAX_INITIAL_LEASE_TICKS);
        return new SemanticBlockSearchTaskRecord(
                context.toolCallId(), context.deadline(initialLease),
                targets, boundedCount, distance);
    }
}
