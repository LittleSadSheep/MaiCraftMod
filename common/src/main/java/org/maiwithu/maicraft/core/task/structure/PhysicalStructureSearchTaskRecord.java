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
import org.maiwithu.maicraft.core.task.explore.ExplorationSector;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;

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
    public final ExplorationSector sector;
    public final TransportMode transportMode;
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
     * 内部组合构造器。排除项是 Mod 自有且类型明确的世界证据，既不是 MCP 参数，也不会包含在公开结果中。
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

    /** 带有明确稀有消耗品许可的内部组合构造器。 */
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
        this(toolCallId, deadlineGameTime, structureId, maxDistance, mayAlterTerrain, reachStructure,
                allowRareConsumables, excludedEvidenceAnchors, evidenceExclusionRadius, null, null, null, TransportMode.AUTO);
    }

    /** 指定方向的结构搜索沿用原生线索，但只接受扇区内的地点；交通方式也随每段路线保留。 */
    public PhysicalStructureSearchTaskRecord(String toolCallId, long deadlineGameTime, String structureId,
            int maxDistance, boolean mayAlterTerrain, boolean reachStructure, boolean allowRareConsumables,
            List<BlockPos> excludedEvidenceAnchors, int evidenceExclusionRadius,
            String direction, Integer angleDegrees, Integer minDistance, TransportMode transportMode) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        ResourceLocation parsed = ResourceLocation.tryParse(structureId);
        if (parsed == null) {
            throw new IllegalArgumentException(
                    "structure_id must be a namespaced id such as minecraft:stronghold");
        }
        this.structureId = parsed.toString();
        this.maxDistance = Math.clamp(maxDistance, MIN_DISTANCE, MAX_DISTANCE);
        this.sector = ExplorationSector.of(direction, angleDegrees, minDistance, this.maxDistance);
        this.transportMode = transportMode == null ? TransportMode.AUTO : transportMode;
        if (this.transportMode != TransportMode.AUTO && this.transportMode != TransportMode.GROUND)
            throw new IllegalArgumentException("structure discovery uses auto or ground transport");
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

    /** 调用此方法会在 Mod 初始化期间强制完成静态任务注册。 */
    public static void ensureRegistered() {}

    @Override
    public String describe() {
        return (reachStructure ? "寻找并到达 " : "寻找 ")
                + structureId + "，范围 " + maxDistance + " 格"
                + (mayAlterTerrain ? "（允许开路）" : "");
    }
}
