// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

/** 读取原生投料任务已经冻结的入池证据；恢复时即使原料已转化，也不能丢掉此前确认的实体身份和数量。 */
final class WorldProcessDropReceipt {
    record Credit(UUID uuid, int entityId, int amount) {}
    private WorldProcessDropReceipt() {}

    // 合堆可以改变实体数量，却不能改变完整物品组件和总量；同一分配器同时处理不同分堆方式与重复输入。
    static boolean sameItems(List<ItemStack> expected, List<ItemStack> actual) {
        long[] available = actual.stream().mapToLong(ItemStack::getCount).toArray();
        long[] demand = expected.stream().mapToLong(ItemStack::getCount).toArray();
        if (java.util.Arrays.stream(available).sum() != java.util.Arrays.stream(demand).sum()) return false;
        return org.maiwithu.maicraft.core.tools.ResourceAllocation.allocate(available, demand,
                (r, i) -> ItemStack.isSameItemSameComponents(actual.get(r), expected.get(i))) != null;
    }

    static List<Credit> read(Map<String, Object> data, ItemStack input, HolderLookup.Provider registries) {
        if (!(data.get("received_entities") instanceof List<?> rows) || rows.isEmpty())
            throw new IllegalStateException("world_process_received_entities_missing");
        List<Credit> credits = new ArrayList<>(); int total = 0;
        for (Object value : rows) {
            if (!(value instanceof Map<?, ?> row) || !(row.get("count") instanceof Number count)
                    || !(row.get("entity_id") instanceof Number entityId) || !(row.get("entity_uuid") instanceof String uuid)
                    || !(row.get("stack") instanceof JsonElement encoded))
                throw new IllegalStateException("world_process_received_entity_invalid");
            ItemStack observed = ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), encoded).getOrThrow();
            int amount = count.intValue();
            // 确认的是本次进入该实体的增量；整堆快照必须保留相同组件，但不能把旧堆总量一并记作本次投出。
            if (amount < 1 || amount > observed.getCount() || !ItemStack.isSameItemSameComponents(input, observed))
                throw new IllegalStateException("world_process_received_entity_identity_mismatch");
            total = Math.addExact(total, amount); credits.add(new Credit(UUID.fromString(uuid), entityId.intValue(), amount));
        }
        if (total != input.getCount()) throw new IllegalStateException("world_process_received_entity_amount_mismatch");
        return List.copyOf(credits);
    }
}
