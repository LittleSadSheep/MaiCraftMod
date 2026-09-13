package org.maiwithu.maicraft.core.integration.ae2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** 施工余料只存普通物品；已确认存入量由玩家侧和网络侧同量反向变化共同证明。 */
final class Ae2DepositLedger {
    enum Verdict { WAITING, CONFIRMED, DIVERGED }
    record Observation(Verdict verdict, int deposited) {}
    private final Map<ResourceLocation, Integer> confirmed = new LinkedHashMap<>();
    private final List<Map<String, Object>> receipts = new ArrayList<>();

    static boolean ordinary(ItemStack stack) {
        return !stack.isEmpty() && stack.getComponentsPatch().isEmpty()
                && ItemStack.isSameItemSameComponents(stack, stack.getItem().getDefaultInstance());
    }
    static long networkCount(List<Ae2ReflectionBridge.Entry> entries, ResourceLocation item) {
        long count = 0;
        for (var entry : entries) if (entry.itemId().equals(item) && ordinary(entry.sample()))
            count = Math.addExact(count, entry.storedAmount());
        return count;
    }
    static Map<ResourceLocation, Integer> inventory(List<ItemStack> stacks) {
        Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : stacks) if (!stack.isEmpty())
            counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount(), Math::addExact);
        return Map.copyOf(counts);
    }

    // AE 原生 Shift 可能只存入网络还能容纳的一部分；两边同量才能记账，不能把丢到别处的物品算作入网。
    static Observation observe(int sourceBefore, int sourceAfter, int inventoryBefore, int inventoryAfter,
                               long networkBefore, long networkAfter, boolean cursorEmpty, boolean otherSlotsUnchanged) {
        int removed = sourceBefore - sourceAfter;
        long increased = networkAfter - networkBefore;
        if (!cursorEmpty || !otherSlotsUnchanged || removed < 0 || removed > sourceBefore
                || inventoryBefore - inventoryAfter < 0 || inventoryBefore - inventoryAfter > sourceBefore
                || increased < 0 || increased > sourceBefore) return new Observation(Verdict.DIVERGED, 0);
        if (removed > 0 && inventoryBefore - inventoryAfter == removed && increased == removed)
            return new Observation(Verdict.CONFIRMED, removed);
        return new Observation(Verdict.WAITING, 0);
    }
    void confirmed(ResourceLocation item, int amount, long networkBefore, long networkAfter, int sourceBefore, int sourceAfter) {
        if (amount < 1 || sourceBefore - sourceAfter != amount || networkAfter - networkBefore != amount)
            throw new IllegalArgumentException("AE deposit receipt does not conserve the exact ordinary item");
        confirmed.merge(item, amount, Math::addExact);
        if (receipts.size() < 128) receipts.add(Map.of("item_id", item.toString(), "deposited", amount,
                "source_before", sourceBefore, "source_after", sourceAfter, "network_before_observed", networkBefore,
                "network_after_observed", networkAfter, "native_gui_shift_click", true));
    }
    int deposited(ResourceLocation item) { return confirmed.getOrDefault(item, 0); }
    Map<ResourceLocation, Integer> counts() { return Map.copyOf(confirmed); }
    Map<String, Object> evidence() {
        Map<String, Integer> items = new LinkedHashMap<>(); confirmed.forEach((id, count) -> items.put(id.toString(), count));
        return Map.of("deposited", Map.copyOf(items), "confirmed_deposited_total", confirmed.values().stream().mapToInt(Integer::intValue).sum(),
                "deposit_receipts", List.copyOf(receipts), "client_observed", true, "server_verified", false,
                "network_observation", "native AE2 client repository; absent entries are observed zero, not a full-sync certificate",
                "server_insert_api_used", false);
    }
}
