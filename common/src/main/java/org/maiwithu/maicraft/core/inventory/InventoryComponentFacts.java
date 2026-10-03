// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Inventory;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;

/** 总数量仍按物品汇总，同时保留带组件的变体，使背包里的不同装配进度无需放到机器上才能辨认。 */
public final class InventoryComponentFacts {
    private InventoryComponentFacts() {}

    /** 背包、穿戴和副手共同组成随身总量；主手本来就在快捷栏，不能再算一次。 */
    public static JsonArray carried(Inventory inventory, HolderLookup.Provider registries) {
        List<ItemStack> stacks = new ArrayList<>();
        Map<String, Map<String, Long>> locations = new LinkedHashMap<>();
        collect(inventory.items, "backpack", stacks, locations);
        collect(inventory.offhand, "off_hand", stacks, locations);
        collect(inventory.armor, "armor", stacks, locations);
        JsonArray result = inventory(stacks, registries);
        for (var entry : result) {
            var row = entry.getAsJsonObject();
            JsonObject counts = new JsonObject();
            locations.get(row.get("item_id").getAsString()).forEach(counts::addProperty);
            // 火把从背包进入副手只改变存放位置；只读取 inventory 段时也必须看得见它的去向。
            row.add("location_counts", counts);
        }
        return result;
    }

    private static void collect(Iterable<ItemStack> group, String location, List<ItemStack> stacks,
                                Map<String, Map<String, Long>> locations) {
        for (ItemStack stack : group) {
            if (stack.isEmpty()) continue;
            stacks.add(stack);
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            locations.computeIfAbsent(id, ignored -> new LinkedHashMap<>()).merge(location, (long) stack.getCount(), Long::sum);
        }
    }

    public static JsonObject observe(ItemStack stack, HolderLookup.Provider registries) {
        JsonObject facts = new JsonObject();
        try {
            JsonObject identity = ResourceIdentity.item(stack, registries);
            facts.addProperty("identity_status", "observed");
            facts.addProperty("resource_id", ResourceIdentity.key(identity));
            facts.add("components", identity.get("components").deepCopy());
        } catch (RuntimeException unavailable) {
            // 真正无法序列化的组件才报未知；已经读到的大组件完整交付，不能让模型额外放入机器才能看进度。
            facts.addProperty("identity_status", "unknown");
            facts.addProperty("identity_issue", "components_unavailable");
        }
        return facts;
    }

    public static JsonArray inventory(Iterable<ItemStack> stacks, HolderLookup.Provider registries) {
        Map<String, Long> totals = new LinkedHashMap<>();
        Map<String, Map<String, JsonObject>> variants = new LinkedHashMap<>();
        var detailed = new LinkedHashSet<String>(); int unknown = 0;
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            totals.merge(id, (long) stack.getCount(), Long::sum);
            JsonObject facts = observe(stack, registries);
            String key = facts.has("resource_id") ? facts.get("resource_id").getAsString() : "unknown-" + unknown++;
            if (!facts.has("components") || !facts.getAsJsonObject("components").isEmpty()) detailed.add(id);
            var sameItem = variants.computeIfAbsent(id, ignored -> new LinkedHashMap<>());
            JsonObject previous = sameItem.get(key);
            if (previous == null) { facts.addProperty("count", stack.getCount()); sameItem.put(key, facts); }
            else previous.addProperty("count", previous.get("count").getAsLong() + stack.getCount());
        }
        JsonArray result = new JsonArray();
        totals.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).forEach(entry -> {
            JsonObject row = new JsonObject(); row.addProperty("item_id", entry.getKey()); row.addProperty("count", entry.getValue());
            // 普通无组件物品保持紧凑；只要有带组件或未知变体，就把同类全部变体列出，避免默认变体数量失踪。
            if (detailed.contains(entry.getKey())) {
                JsonArray values = new JsonArray(); variants.get(entry.getKey()).values().forEach(values::add); row.add("variants", values);
                row.addProperty("count_scope", "registry-item total; variants preserve serialized component patches and exact resource_id");
            }
            result.add(row);
        });
        return result;
    }
}
