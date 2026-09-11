// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mekanism;

import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** Native machine recipe lists include generated smelting recipes absent from vanilla's manager. */
public final class MekanismRecipeAccess {
    public static final String LOOKUP = "mekanism.common.recipe.lookup.IRecipeLookupHandler";
    public static final String PROVIDER = "mekanism.common.recipe.IMekanismRecipeTypeProvider";
    private MekanismRecipeAccess() {}

    public static Optional<RecipeHolder<?>> find(ServerPlayer player, BlockEntity entity, ResourceLocation requested) {
        if (!NativeApi.is(entity, LOOKUP)) return Optional.empty();
        Object provider = NativeApi.call(entity, LOOKUP, "getRecipeType");
        List<?> recipes = (List<?>) NativeApi.call(provider, PROVIDER, "getRecipes", player.serverLevel());
        if (recipes.size() > 8192) return Optional.empty();
        ResourceLocation generated = null;
        var vanilla = player.serverLevel().getRecipeManager().byKey(requested);
        if (vanilla.isPresent() && vanilla.get().value().getType() == RecipeType.SMELTING) {
            Object actualType = NativeApi.call(provider, PROVIDER, "getRecipeType");
            Object smelting = NativeApi.call(NativeApi.constant("mekanism.common.recipe.MekanismRecipeType", "SMELTING"),
                    PROVIDER, "getRecipeType");
            if (actualType == smelting) {
                // Invoke the same native alias function used by Mekanism's server recipe-list conversion.
                generated = (ResourceLocation) NativeApi.call(null, "mekanism.client.recipe_viewer.RecipeViewerUtils",
                        "synthetic", requested, "mekanism_generated");
            }
        }
        RecipeHolder<?> alias = null;
        for (Object entry : recipes) {
            if (!(entry instanceof RecipeHolder<?> holder)) continue;
            if (holder.id().equals(requested)) return Optional.of(holder);
            if (holder.id().equals(generated)) alias = holder;
        }
        return Optional.ofNullable(alias);
    }

    public static boolean compatible(BlockEntity entity, RecipeHolder<?> holder) {
        if (!NativeApi.is(entity, LOOKUP)) return false;
        Object provider = NativeApi.call(entity, LOOKUP, "getRecipeType");
        // No getRecipe(currentInput) call: empty inventories do not alter supported recipe types.
        return NativeApi.call(provider, PROVIDER, "getRecipeType") == holder.value().getType();
    }
}
