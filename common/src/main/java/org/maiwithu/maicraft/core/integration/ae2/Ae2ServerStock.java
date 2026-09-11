// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Converts authoritative exact-key pages into the existing AE2 capacity/allocation planner inputs. */
final class Ae2ServerStock {
    private final Map<String, Ae2ReflectionBridge.Entry> entries = new LinkedHashMap<>();
    private final Map<Long, String> identities = new LinkedHashMap<>();
    private final Map<Long, Long> remaining = new LinkedHashMap<>();
    private String membership;

    void append(JsonObject page, HolderLookup.Provider registries) {
        if (!page.has("schema") || !page.get("schema").getAsString().equals("maicraft.ae2_network.v1"))
            throw new IllegalArgumentException("unexpected AE2 network schema");
        String network = page.get("membership").getAsString();
        if (membership != null && !membership.equals(network)) throw new IllegalStateException("AE2 network membership changed");
        membership = network;
        for (var raw : page.getAsJsonArray("resources")) {
            JsonObject resource = raw.getAsJsonObject();
            JsonObject identity = resource.getAsJsonObject("identity");
            if (!identity.get("kind").getAsString().equals("items")) continue;
            String key = resource.get("resource_id").getAsString();
            if (entries.containsKey(key)) continue;
            if (entries.size() >= 2048) throw new IllegalArgumentException("AE2 stock page budget exceeded");
            JsonObject encoded = new JsonObject();
            encoded.add("id", identity.get("id").deepCopy());
            encoded.addProperty("count", 1);
            encoded.add("components", identity.get("components").deepCopy());
            ItemStack sample = ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), encoded).getOrThrow();
            long amount = resource.get("amount").getAsBigDecimal().longValueExact();
            if (sample.isEmpty() || amount < 0) throw new IllegalArgumentException("invalid authoritative item stock");
            long serial = entries.size() + 1L;
            var entry = new Ae2ReflectionBridge.Entry(ResourceLocation.parse(identity.get("id").getAsString()),
                    serial, amount, resource.has("craftable") && resource.get("craftable").getAsBoolean(), sample);
            entries.put(key, entry);
            identities.put(serial, key);
            remaining.put(serial, amount);
        }
    }

    List<Ae2ReflectionBridge.Entry> entries() { return List.copyOf(entries.values()); }
    String membership() { return membership; }
    String identity(long serial) { return identities.get(serial); }

    Ae2ReflectionBridge.Entry select(Ae2SupplyPlanner.Allocation allocation) {
        return entries.values().stream().filter(entry -> remaining.get(entry.serial()) > 0
                && entry.itemId().equals(allocation.itemId())
                && ItemStack.isSameItemSameComponents(entry.sample(), allocation.sample())).findFirst().orElse(null);
    }

    long remaining(long serial) { return remaining.getOrDefault(serial, 0L); }
    Ae2ReflectionBridge.Entry craftable(Ae2SupplyPlanner.Allocation allocation) {
        return entries.values().stream().filter(entry -> entry.craftable()
                && entry.itemId().equals(allocation.itemId())
                && ItemStack.isSameItemSameComponents(entry.sample(), allocation.sample())).findFirst().orElse(null);
    }
    void clear() { entries.clear(); identities.clear(); remaining.clear(); }
    void debit(long serial, int amount) {
        long available = remaining(serial);
        if (amount < 0 || amount > available) throw new IllegalArgumentException("transfer exceeds observed exact-key stock");
        remaining.put(serial, available - amount);
    }
}
