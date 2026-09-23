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

/** 原生机器配方列表包含自动生成的熔炼配方，而原版管理器中没有这些配方。 */
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
                // 调用 Mekanism 服务端配方列表转换所使用的同一个原生别名函数。
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
        // 不调用 getRecipe(currentInput)：空库存不会改变受支持的配方类型。
        return NativeApi.call(provider, PROVIDER, "getRecipeType") == holder.value().getType();
    }
}
