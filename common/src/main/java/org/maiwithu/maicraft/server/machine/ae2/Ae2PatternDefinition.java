// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.ae2;

import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;

/** A recipe-validated in-memory definition; this never reads material stock or writes a provider. */
record Ae2PatternDefinition(ItemStack pattern, String mode, String requestedId, String recipeId) {
    static Ae2PatternDefinition resolve(ServerPlayer player, JsonObject body) {
        for (String field : body.keySet()) if (!Set.of("position", "action", "side", "recipe_id", "mode").contains(field)) {
            throw ServerAccess.denied("invalid_argument", "Pattern IO must come from the server recipe, not argument " + field);
        }
        String mode = ServerAccess.text(body, "mode"), requested = ServerAccess.text(body, "recipe_id"), canonical = requested;
        ItemStack encoded;
        if (mode.equals("crafting")) {
            ResourceLocation id = ResourceLocation.tryParse(requested);
            if (id == null) throw ServerAccess.denied("invalid_argument", "Invalid recipe identifier");
            var holder = player.serverLevel().getRecipeManager().byKey(id)
                    .orElseThrow(() -> ServerAccess.denied("recipe_missing", "Crafting recipe is absent from the server"));
            encoded = Ae2CraftingPatternEncoding.encode(player, holder);
        } else if (mode.equals("processing")) {
            var result = Ae2ProcessingPatternEncoding.encode(player, body); encoded = result.pattern(); canonical = result.recipeId();
        } else throw ServerAccess.denied("invalid_argument", "Pattern mode must be crafting or processing");
        if (NativeApi.call(null, "appeng.api.crafting.PatternDetailsHelper", "decodePattern", encoded, player.serverLevel()) == null) {
            throw ServerAccess.denied("unsupported_recipe", "AE2 rejected the native recipe definition");
        }
        return new Ae2PatternDefinition(encoded, mode, requested, canonical);
    }

    static boolean equivalent(ItemStack existing, ItemStack expected, String mode) {
        if (existing.isEmpty() || !existing.is(expected.getItem())) return false;
        DataComponentType<?> type = (DataComponentType<?>) NativeApi.constant("appeng.api.ids.AEComponents",
                mode.equals("crafting") ? "ENCODED_CRAFTING_PATTERN" : "ENCODED_PROCESSING_PATTERN");
        return expected.get(type) != null && Objects.equals(existing.get(type), expected.get(type));
    }
}
