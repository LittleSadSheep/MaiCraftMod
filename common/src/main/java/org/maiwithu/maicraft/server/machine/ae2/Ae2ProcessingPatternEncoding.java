// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.ae2;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.server.inventory.Ae2Access;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;
import org.maiwithu.maicraft.server.machine.ServerMachineRecipe;

/** Processing IO comes exclusively from the server's complete native recipe normalizer. */
final class Ae2ProcessingPatternEncoding {
    record Result(ItemStack pattern, String recipeId) {}
    private Ae2ProcessingPatternEncoding() {}

    static Result encode(ServerPlayer player, JsonObject body) {
        JsonObject query = new JsonObject();
        query.add("position", body.get("position").deepCopy());
        query.addProperty("recipe_id", ServerAccess.text(body, "recipe_id"));
        JsonObject recipe = ServerMachineRecipe.inspect(player, query);
        if (!recipe.has("complete") || !recipe.get("complete").getAsBoolean()) {
            throw ServerAccess.denied("unsupported_processing_recipe", "The server cannot completely normalize this processing recipe");
        }
        List<Object> inputs = new ArrayList<>(), outputs = new ArrayList<>();
        for (JsonElement raw : recipe.getAsJsonArray("inputs")) {
            JsonObject input = raw.getAsJsonObject();
            if (!input.has("consumed") || !input.get("consumed").getAsBoolean()) {
                throw ServerAccess.denied("unsupported_processing_recipe", "Reusable catalysts need an explicit native pattern representation");
            }
            JsonArray candidates = input.getAsJsonArray("alternatives");
            JsonObject selected = candidates.asList().stream().map(JsonElement::getAsJsonObject)
                    .min(Comparator.comparing(value -> value.get("id").getAsString()))
                    .orElseThrow(() -> ServerAccess.denied("unsupported_processing_recipe", "Recipe input has no exact alternative"));
            inputs.add(Ae2ConfigurationAccess.generic(key(player, selected), positiveAmount(input)));
        }
        for (JsonElement raw : recipe.getAsJsonArray("outputs")) {
            JsonObject output = raw.getAsJsonObject();
            if (!output.has("chance") || output.get("chance").getAsBigDecimal().compareTo(BigDecimal.ONE) != 0) {
                throw ServerAccess.denied("unsupported_processing_recipe", "Probabilistic outputs cannot be promised as guaranteed AE2 job outputs");
            }
            outputs.add(Ae2ConfigurationAccess.generic(key(player, output.getAsJsonObject("resource")), positiveAmount(output)));
        }
        int maxInputs = (int) NativeApi.number(NativeApi.constant("appeng.crafting.pattern.AEProcessingPattern", "MAX_INPUT_SLOTS"));
        int maxOutputs = (int) NativeApi.number(NativeApi.constant("appeng.crafting.pattern.AEProcessingPattern", "MAX_OUTPUT_SLOTS"));
        if (inputs.isEmpty() || outputs.isEmpty() || inputs.size() > maxInputs || outputs.size() > maxOutputs) {
            throw ServerAccess.denied("recipe_limit", "Recipe exceeds native AE2 processing-pattern dimensions");
        }
        ItemStack pattern = (ItemStack) NativeApi.call(null, "appeng.api.crafting.PatternDetailsHelper", "encodeProcessingPattern", inputs, outputs);
        return new Result(pattern, recipe.get("recipe_id").getAsString());
    }

    private static long positiveAmount(JsonObject value) {
        try {
            long result = value.get("amount").getAsBigDecimal().longValueExact();
            if (result <= 0) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException invalid) { throw ServerAccess.denied("unsupported_processing_recipe", "Recipe amount is not an exact positive integer"); }
    }

    @SuppressWarnings("unchecked")
    private static Object key(ServerPlayer player, JsonObject resource) {
        JsonObject identity = resource.getAsJsonObject("identity");
        if (identity == null || !ResourceIdentity.key(identity).equals(resource.get("id").getAsString())) {
            throw ServerAccess.denied("unsupported_resource", "Native recipe resource identity is incomplete");
        }
        JsonObject encoded = new JsonObject(); encoded.add("id", identity.get("id"));
        encoded.add("components", identity.get("components").deepCopy());
        String medium = resource.get("medium").getAsString();
        if (medium.equals("items")) {
            encoded.addProperty("count", 1);
            ItemStack sample = ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, player.registryAccess()), encoded).getOrThrow();
            if (!ResourceIdentity.key(ResourceIdentity.item(sample, player.registryAccess())).equals(resource.get("id").getAsString())) {
                throw ServerAccess.denied("unsupported_resource", "Item pattern encoding would lose data components");
            }
            return NativeApi.call(null, Ae2Access.ITEM, "of", sample);
        }
        if (medium.equals("fluids") && NativeApi.present("net.neoforged.neoforge.fluids.FluidStack")) {
            encoded.addProperty("amount", 1);
            Codec<Object> codec = (Codec<Object>) NativeApi.constant("net.neoforged.neoforge.fluids.FluidStack", "CODEC");
            Object sample = codec.parse(RegistryOps.create(JsonOps.INSTANCE, player.registryAccess()), encoded).getOrThrow();
            return NativeApi.call(null, "appeng.api.stacks.AEFluidKey", "of", sample);
        }
        throw ServerAccess.denied("unsupported_resource", "No verified native AE2 key encoder is available for recipe medium " + medium);
    }
}
