// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** 冻结附近农田的搜索中心；只收成熟作物，补种完成后的主背包总数才用于满足取材目标。 */
public final class HarvestCropTaskRecord extends TaskRecord {
    static { TaskFactory.register(HarvestCropTaskRecord.class, HarvestCropCompanionTask::new); }
    public final List<Item> items;
    public final int count, radius;
    public final BlockPos origin;
    public HarvestCropTaskRecord(String id, long deadline, List<Item> items, int count, BlockPos origin, int radius) {
        super("harvest_crops", id, deadline);
        if (count < 1 || count > 2304 || radius < 1 || radius > 48) throw new IllegalArgumentException("invalid crop harvest quantity or radius");
        this.items = List.copyOf(items); this.count = count; this.origin = origin.immutable(); this.radius = radius;
    }
}
