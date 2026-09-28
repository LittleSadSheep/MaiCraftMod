// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.integration.backpack.BackpackMenuAccess;
import org.maiwithu.maicraft.core.integration.backpack.BackpackStock;
import org.maiwithu.maicraft.core.integration.backpack.BackpackSupplyTaskRecord;

/** 每项材料先在本人携带的背包逐只找现货；来源许可与观察失败的含义不会因此被扩大或抹掉。 */
final class AcquisitionBackpackInventory {
    private final Function<LocalPlayer, List<Integer>> carried;
    private final Map<AcquisitionNeed, ArrayDeque<Integer>> remaining = new LinkedHashMap<>();
    AcquisitionBackpackInventory() { this(BackpackStock::carriedSlots); }
    // 只把随身入口发现留作回放注入口；真正操作仍创建同一种原生背包事务任务。
    AcquisitionBackpackInventory(Function<LocalPlayer, List<Integer>> carried) { this.carried = carried; }

    BackpackSupplyTaskRecord next(LocalPlayer player, AcquisitionNeed need, int missing, Supplier<String> id, long deadline) {
        if (missing <= 0 || need.decisionRequired || !permitted(need.allowedSources)) return null;
        var slots = remaining.computeIfAbsent(need, ignored -> new ArrayDeque<>(carried.apply(player)));
        if (slots.isEmpty()) return null;
        var known = BackpackStock.known(player);
        while (!slots.isEmpty()) {
            int slot = slots.removeFirst();
            String storage = BackpackMenuAccess.contentsIdentity(player.getInventory().getItem(slot));
            var view = known.stream().filter(value -> value.storageId().equals(storage)).findFirst();
            // 只有新鲜观察能证明这只包没有候选物品；没有观察的包必须真的打开查询，不能按零库存跳过。
            if (view.isPresent() && need.itemIds.stream().noneMatch(item -> view.get().stored().getOrDefault(item, 0L) > 0)) continue;
            return new BackpackSupplyTaskRecord(id.get(), deadline, slot, BackpackSupplyTaskRecord.Operation.WITHDRAW, need.itemIds, missing, Map.of());
        }
        return null;
    }
    static boolean permitted(List<SemanticAcquireTaskRecord.Source> sources) {
        return sources.contains(SemanticAcquireTaskRecord.Source.INVENTORY) || sources.contains(SemanticAcquireTaskRecord.Source.STORAGE);
    }
    void retryAfterCapacity(AcquisitionNeed need, int slot) {
        // 清包确认成功后允许重取刚才因容量受阻的同一只包，不把满包记成该存储已经没货。
        var pending = remaining.computeIfAbsent(need, ignored -> new ArrayDeque<>());
        if (!pending.contains(slot)) pending.addFirst(slot);
    }
}
