// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.create;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** Native item predicates and crusher's crushing-before-milling precedence, without replacing live machine input. */
final class CreateGrindingRecipeAccess {
    static final String MILL = "com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity";
    static final String CRUSH = "com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity";
    static final String MILLING = "com.simibubi.create.content.kinetics.millstone.MillingRecipe";
    static final String CRUSHING = "com.simibubi.create.content.kinetics.crusher.CrushingRecipe";
    private record Cached(WeakReference<RecipeManager> manager, ResourceLocation id) {}
    private static final Map<Object, Cached> IDS = new WeakHashMap<>();
    record Selection(RecipeHolder<?> holder, boolean complete) {}
    private CreateGrindingRecipeAccess() {}

    static Selection select(ServerLevel level, boolean crusher, ItemStack sample) {
        SingleRecipeInput input = new SingleRecipeInput(sample.copy());
        Selection crushing = crusher ? matching(level, "create:crushing", input) : new Selection(null, true);
        if (!crushing.complete() || crushing.holder() != null) return crushing;
        return matching(level, "create:milling", input);
    }

    private static Selection matching(ServerLevel level, String type, SingleRecipeInput input) {
        RecipeHolder<?> found = null;
        int visited = 0;
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            if (++visited > 65_536) return new Selection(null, false);
            var recipe = holder.value();
            if (!type.equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString())) continue;
            if (!NativeApi.truth(NativeApi.call(recipe, null, "matches", input, level))) continue;
            // A millstone can retain a matching cached recipe. Ambiguous predicates cannot identify that private cache.
            if (found != null) return new Selection(null, false);
            found = holder;
        }
        return new Selection(found, true);
    }

    static String id(ServerLevel level, Object recipe) {
        RecipeManager manager = level.getRecipeManager();
        Cached cached = IDS.get(recipe);
        if (cached != null && cached.manager().get() == manager
                && manager.byKey(cached.id()).map(holder -> holder.value() == recipe).orElse(false)) return cached.id().toString();
        ResourceLocation found = null;
        int visited = 0;
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (++visited > 65_536) return null;
            if (holder.value() != recipe) continue;
            if (found != null && !found.equals(holder.id())) return null;
            found = holder.id();
        }
        if (found != null && IDS.size() < 4096) IDS.put(recipe, new Cached(new WeakReference<>(manager), found));
        return found == null ? null : found.toString();
    }
}
