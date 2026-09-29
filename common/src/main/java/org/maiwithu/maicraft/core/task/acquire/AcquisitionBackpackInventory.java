// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.integration.backpack.BackpackCarriers.Carrier;
import org.maiwithu.maicraft.core.integration.backpack.BackpackStock;
import org.maiwithu.maicraft.core.integration.backpack.BackpackSupplyTaskRecord;

/** 每项材料先在本人携带的背包逐只找现货；来源许可与观察失败的含义不会因此被扩大或抹掉。 */
final class AcquisitionBackpackInventory {
    private final Function<LocalPlayer, List<Carrier>> carried;
    private final Map<AcquisitionNeed, ArrayDeque<Carrier>> remaining = new LinkedHashMap<>();
    AcquisitionBackpackInventory() { this(BackpackStock::carriers); }
    // 只把随身入口发现留作回放注入口；真正操作仍创建同一种原生背包事务任务。
    AcquisitionBackpackInventory(Function<LocalPlayer, List<Carrier>> carried) { this.carried = carried; }

    BackpackSupplyTaskRecord next(LocalPlayer player, AcquisitionNeed need, int missing, Supplier<String> id, long deadline) {
        if (missing <= 0 || need.decisionRequired || !permitted(need.allowedSources)) return null;
        var slots = remaining.computeIfAbsent(need, ignored -> new ArrayDeque<>(carried.apply(player)));
        if (slots.isEmpty()) return null;
        // 自动拾取升级可能在关包后补入材料；每项需求都先读原生库存，缓存中的旧零库存不能让角色直接外出。
        return new BackpackSupplyTaskRecord(id.get(), deadline, slots.removeFirst(), BackpackSupplyTaskRecord.Operation.WITHDRAW, need.itemIds, missing, Map.of());
    }
    static boolean permitted(List<SemanticAcquireTaskRecord.Source> sources) {
        return sources.contains(SemanticAcquireTaskRecord.Source.INVENTORY) || sources.contains(SemanticAcquireTaskRecord.Source.STORAGE);
    }
    void retryAfterCapacity(AcquisitionNeed need, Carrier slot) {
        // 清包确认成功后允许重取刚才因容量受阻的同一只包，不把满包记成该存储已经没货。
        var pending = remaining.computeIfAbsent(need, ignored -> new ArrayDeque<>());
        if (!pending.contains(slot)) pending.addFirst(slot);
    }
}
