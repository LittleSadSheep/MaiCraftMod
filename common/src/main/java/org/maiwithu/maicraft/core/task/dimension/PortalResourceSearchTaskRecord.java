// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import net.minecraft.core.BlockPos;
import java.util.Set;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** 备门前置搜索只声明缺哪种资源和可探索范围，实际路线继续由现有探索任务执行。 */
final class PortalResourceSearchTaskRecord extends TaskRecord {
    enum Resource { WATER, LAVA_SOURCE, LAVA_POOL }
    static { TaskFactory.register(PortalResourceSearchTaskRecord.class, PortalResourceSearchTask::new); }
    final Resource resource;
    final int loadedRadius, explorationDistance;
    final boolean mayAlterTerrain;
    Set<BlockPos> excluded = Set.of();
    BlockPos observedPosition;

    PortalResourceSearchTaskRecord(String id, long deadline, Resource resource, int loadedRadius,
                                   int explorationDistance, boolean mayAlterTerrain) {
        super("portal_resource_search", id, deadline);
        this.resource = resource; this.loadedRadius = Math.clamp(loadedRadius, 4, 128);
        this.explorationDistance = explorationDistance; this.mayAlterTerrain = mayAlterTerrain;
    }
    @Override public String describe() { return switch (resource) {
        case WATER -> "寻找浇筑用的可取水源";
        case LAVA_SOURCE -> "寻找可原生回倒岩浆的已有源格";
        case LAVA_POOL -> "寻找可浇筑的岩浆池";
    }; }
}
