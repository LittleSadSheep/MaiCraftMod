// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.backpack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.core.inventory.StockEvidence;

/** 随身背包库存只来自已同步菜单；未打开、离身、过期或背包数量变化都不再冒充当前现货。 */
public final class BackpackStock {
    private static final Cache CACHE = new Cache();
    private BackpackStock() {}
    public static List<Integer> carriedSlots(LocalPlayer player) {
        var slots = new ArrayList<Integer>();
        for (int i = 0; i < 36; i++) if (BackpackMenuAccess.isBackpack(player.getInventory().getItem(i))) slots.add(i);
        if (BackpackMenuAccess.isBackpack(player.getOffhandItem())) slots.add(40);
        return List.copyOf(slots);
    }
    public static void observe(LocalPlayer player) {
        if (player == null || player != Minecraft.getInstance().player || player.level() != Minecraft.getInstance().level) { CACHE.clear(); return; }
        var read = BackpackMenuAccess.read(player);
        if (read.snapshot() != null) record(player, read.snapshot());
    }
    public static void record(LocalPlayer player, BackpackMenuAccess.Snapshot view) {
        CACHE.record(player, player.level(), mainCounts(player), view);
    }
    public static List<BackpackMenuAccess.Snapshot> known(LocalPlayer player) {
        Set<String> identities = new LinkedHashSet<>();
        for (int slot : carriedSlots(player)) {
            String id = BackpackMenuAccess.contentsIdentity(player.getInventory().getItem(slot));
            if (id != null) identities.add(id);
        }
        return CACHE.latest(player, player.level(), mainCounts(player), identities, player.level().getGameTime());
    }
    public static Map<String, Object> facts(LocalPlayer player) {
        // 感知只读当前可见菜单和有时效的观察，不会为了回答库存偷偷打开背包。
        observe(player);
        var carried = carriedSlots(player); var known = known(player);
        var counts = new LinkedHashMap<ResourceLocation, Long>(); var extractable = new LinkedHashMap<ResourceLocation, Long>();
        var sources = new ArrayList<Map<String, Object>>();
        for (var view : known) {
            view.stored().forEach((id, amount) -> counts.merge(id, amount, Math::addExact));
            view.extractable().forEach((id, amount) -> extractable.merge(id, amount, Math::addExact));
            sources.add(Map.of("storage_id", view.storageId(), "observed_game_tick", view.observedTick(), "infinite_slot_count", view.infiniteSlots().size()));
        }
        long covered = carried.stream().filter(slot -> known.stream().anyMatch(view -> view.storageId().equals(
                BackpackMenuAccess.contentsIdentity(player.getInventory().getItem(slot))))).count();
        var rows = counts.keySet().stream().sorted().limit(64).map(id -> Map.<String, Object>of("item_id", id.toString(),
                "stored", counts.get(id), "extractable", extractable.getOrDefault(id, 0L))).toList();
        return Map.of("source", "sophisticated_backpack", "scope", "main_inventory_and_offhand",
                "carried_backpacks", carried.size(), "unobserved_backpacks", carried.size() - covered,
                "all_carried_backpacks_observed", carried.size() == covered, "observations", sources,
                "items", rows, "omitted_item_types", Math.max(0, counts.size() - rows.size()),
                "fresh_transfer_check_required", true);
    }
    private static Map<ResourceLocation, Long> mainCounts(LocalPlayer player) {
        var counts = new LinkedHashMap<ResourceLocation, Long>();
        for (int i = 0; i < 36; i++) {
            var stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()), (long) stack.getCount(), Math::addExact);
        }
        return counts;
    }

    static final class Cache {
        private Object owner, world;
        private Map<ResourceLocation, Long> inventory = Map.of();
        private final Map<String, BackpackMenuAccess.Snapshot> observations = new LinkedHashMap<>();
        private void bind(Object owner, Object world, Map<ResourceLocation, Long> inventory) {
            // 只交换快捷栏不改变总量；真正存取、消耗或拾取后撤销旧提示，不能猜哪只背包少了物品。
            if (this.owner != owner || this.world != world || !this.inventory.equals(inventory)) observations.clear();
            this.owner = owner; this.world = world; this.inventory = Map.copyOf(inventory);
        }
        void record(Object owner, Object world, Map<ResourceLocation, Long> inventory, BackpackMenuAccess.Snapshot view) {
            bind(owner, world, inventory);
            // 无持久身份的空包与未知链接包装只能用于当前菜单事务，不跨关包加入可用库存合计。
            if (view.storageId().startsWith("sophisticated_backpack:")) observations.put(view.storageId(), view);
        }
        List<BackpackMenuAccess.Snapshot> latest(Object owner, Object world, Map<ResourceLocation, Long> inventory,
                Collection<String> carried, long tick) {
            bind(owner, world, inventory);
            observations.entrySet().removeIf(entry -> !carried.contains(entry.getKey()) || tick < entry.getValue().observedTick()
                    || tick - entry.getValue().observedTick() > StockEvidence.MAX_AGE_TICKS);
            return List.copyOf(observations.values());
        }
        void clear() { owner = null; world = null; inventory = Map.of(); observations.clear(); }
    }
}
