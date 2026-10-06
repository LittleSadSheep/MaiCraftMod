// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import org.maiwithu.maicraft.core.task.inventory.OffhandSupplyTaskRecord;

/**
 * 每项需求最多安排一次副手换位：副手握着点名物品而主背包口径数不到它时，
 * 先把它换进主背包再谈采集。来源许可沿用随身背包的口径，不因换位扩大任何采集许可。
 */
final class AcquisitionOffhandInventory {
    private final Set<AcquisitionNeed> attempted = Collections.newSetFromMap(new IdentityHashMap<>());

    OffhandSupplyTaskRecord next(LocalPlayer player, AcquisitionNeed need, int missing,
            Supplier<String> id, long deadline) {
        if (missing <= 0 || need.decisionRequired || !AcquisitionBackpackInventory.permitted(need.allowedSources))
            return null;
        var offhand = player.getOffhandItem();
        // 只在确实安排了换位时记下这个需求；早到的空副手不能关掉以后换位的入口。
        if (offhand.isEmpty() || !attempted.add(need)) return null;
        if (need.itemIds.stream().noneMatch(itemId -> offhand.is(BuiltInRegistries.ITEM.get(itemId)))) {
            attempted.remove(need);
            return null;
        }
        return new OffhandSupplyTaskRecord(id.get(), deadline, List.copyOf(need.itemIds), missing);
    }
}
