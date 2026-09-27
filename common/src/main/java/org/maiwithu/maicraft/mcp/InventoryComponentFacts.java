// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;

/** 总数量仍按物品汇总，同时保留带组件的变体，使背包里的不同装配进度无需放到机器上才能辨认。 */
final class InventoryComponentFacts {
    private InventoryComponentFacts() {}

    static JsonObject observe(ItemStack stack, HolderLookup.Provider registries) {
        JsonObject facts = new JsonObject();
        try {
            JsonObject identity = ResourceIdentity.item(stack, registries);
            if (identity.toString().length() > 4096) throw new IllegalArgumentException("component_identity_exceeds_budget");
            facts.addProperty("identity_status", "observed");
            facts.addProperty("resource_id", ResourceIdentity.key(identity));
            facts.add("components", identity.get("components").deepCopy());
        } catch (RuntimeException unavailable) {
            // 无法序列化或过大的组件只报未知；不能把未读出的进度伪装成默认空组件。
            facts.addProperty("identity_status", "unknown");
            facts.addProperty("identity_issue", "components_unavailable_or_outside_budget");
        }
        return facts;
    }

    static JsonArray inventory(Iterable<ItemStack> stacks, HolderLookup.Provider registries) {
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
