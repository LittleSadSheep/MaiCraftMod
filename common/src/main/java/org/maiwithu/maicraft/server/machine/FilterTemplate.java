// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Ghost filter criteria only. These stacks must never enter an inventory, entity or resource transfer. */
final class FilterTemplate {
    private FilterTemplate() {}

    static ItemStack resolve(ServerPlayer player, JsonObject body) {
        if (body.has("clear") && ServerAccess.bool(body, "clear")) return ItemStack.EMPTY;
        if (!body.has("item_id")) {
            if (!body.has("player_slot")) throw ServerAccess.denied("invalid_argument", "Specify a filter item_id");
            ItemStack sample = player.getInventory().getItem(ServerAccess.integer(body, "player_slot", 0, 35));
            if (sample.isEmpty()) throw ServerAccess.denied("requires_sample", "The selected filter sample is empty");
            return sample.copyWithCount(1);
        }
        ResourceLocation id = ResourceLocation.tryParse(ServerAccess.text(body, "item_id"));
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) throw ServerAccess.denied("unknown_item", "Filter item is not registered");
        JsonObject encoded = new JsonObject();
        encoded.addProperty("id", id.toString()); encoded.addProperty("count", 1);
        if (body.has("components")) {
            if (!body.get("components").isJsonObject()) throw ServerAccess.denied("invalid_argument", "Filter components must be a component map");
            encoded.add("components", body.get("components").deepCopy());
        }
        try {
            ItemStack template = ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, player.registryAccess()), encoded).getOrThrow();
            if (template.isEmpty()) throw new IllegalArgumentException("Empty filter item");
            return template;
        } catch (RuntimeException invalid) { throw ServerAccess.denied("invalid_filter_components", "Native item codec rejected filter criteria"); }
    }
}
