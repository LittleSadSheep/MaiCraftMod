// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.structure;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 保存结构搜索的范围、是否必须走到线索，以及开路和投眼的许可。
 * 父任务还可以排除已经查过的地点；这些内部地点不由公开工具要求用户逐个提供。
 */
public final class PhysicalStructureSearchTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "structure_search";
    public static final int MIN_DISTANCE = 64;
    public static final int MAX_DISTANCE = 4_096;
    public static final int DEFAULT_DISTANCE = 4_096;

    static {
        TaskFactory.register(
                PhysicalStructureSearchTaskRecord.class,
                PhysicalStructureSearchCompanionTask::new);
    }

    public final String structureId;
    public final int maxDistance;
    public final boolean mayAlterTerrain;
    public final boolean reachStructure;
    public final boolean allowRareConsumables;
    final List<BlockPos> excludedEvidenceAnchors;
    final int evidenceExclusionRadius;

    public PhysicalStructureSearchTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            String structureId,
            int maxDistance,
            boolean mayAlterTerrain,
            boolean reachStructure) {
        this(toolCallId, deadlineGameTime, structureId, maxDistance,
                mayAlterTerrain, reachStructure, false, List.of(), 0);
    }

    public PhysicalStructureSearchTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            String structureId,
            int maxDistance,
            boolean mayAlterTerrain,
            boolean reachStructure,
            boolean allowRareConsumables) {
        this(toolCallId, deadlineGameTime, structureId, maxDistance,
                mayAlterTerrain, reachStructure, allowRareConsumables, List.of(), 0);
    }

    /**
     * Internal composition constructor. Exclusions are typed world evidence owned by the Mod,
     * never MCP arguments and never included in the public result.
     */
    public PhysicalStructureSearchTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            String structureId,
            int maxDistance,
            boolean mayAlterTerrain,
            boolean reachStructure,
            List<BlockPos> excludedEvidenceAnchors,
            int evidenceExclusionRadius) {
        this(toolCallId, deadlineGameTime, structureId, maxDistance,
                mayAlterTerrain, reachStructure, false,
                excludedEvidenceAnchors, evidenceExclusionRadius);
    }

    /** Internal composition constructor with an explicit rare-consumable permission. */
    public PhysicalStructureSearchTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            String structureId,
            int maxDistance,
            boolean mayAlterTerrain,
            boolean reachStructure,
            boolean allowRareConsumables,
            List<BlockPos> excludedEvidenceAnchors,
            int evidenceExclusionRadius) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        ResourceLocation parsed = ResourceLocation.tryParse(structureId);
        if (parsed == null) {
            throw new IllegalArgumentException(
                    "structure_id must be a namespaced id such as minecraft:stronghold");
        }
        this.structureId = parsed.toString();
        this.maxDistance = Math.clamp(maxDistance, MIN_DISTANCE, MAX_DISTANCE);
        this.mayAlterTerrain = mayAlterTerrain;
        this.reachStructure = reachStructure;
        this.allowRareConsumables = allowRareConsumables;
        List<BlockPos> exclusions = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        if (excludedEvidenceAnchors != null) {
            for (BlockPos position : excludedEvidenceAnchors) {
                if (position != null && seen.add(position.asLong())) {
                    exclusions.add(position.immutable());
                }
            }
        }
        this.excludedEvidenceAnchors = List.copyOf(exclusions);
        this.evidenceExclusionRadius = Math.clamp(evidenceExclusionRadius, 0, 256);
    }

    /** Calling this method forces static task registration during Mod initialization. */
    public static void ensureRegistered() {}

    @Override
    public String describe() {
        return (reachStructure ? "寻找并到达 " : "寻找 ")
                + structureId + "，范围 " + maxDistance + " 格"
                + (mayAlterTerrain ? "（允许开路）" : "");
    }
}
