// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.ae2;

import java.util.Arrays;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;

/** Constructs only a native-validated 3x3 recipe template, never a real crafting output inventory. */
final class Ae2CraftingPatternEncoding {
    private Ae2CraftingPatternEncoding() {}

    static ItemStack encode(ServerPlayer player, RecipeHolder<?> holder) {
        if (!(holder.value() instanceof CraftingRecipe recipe) || recipe.isSpecial() || !recipe.canCraftInDimensions(3, 3)) {
            throw ServerAccess.denied("unsupported_recipe", "A concrete native 3x3 crafting recipe is required");
        }
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty() || ingredients.size() > 9) throw ServerAccess.denied("unsupported_recipe", "Crafting ingredients are not bounded to a 3x3 grid");
        ItemStack[] grid = new ItemStack[9]; Arrays.fill(grid, ItemStack.EMPTY);
        int width = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : 3;
        for (int index = 0; index < ingredients.size(); index++) {
            Ingredient ingredient = ingredients.get(index);
            if (ingredient.isEmpty()) continue;
            int slot = recipe instanceof ShapedRecipe ? (index / width) * 3 + index % width : index;
            grid[slot] = candidate(player, ingredient);
        }
        CraftingInput input = CraftingInput.of(3, 3, Arrays.asList(grid));
        if (!recipe.matches(input, player.serverLevel())) throw ServerAccess.denied("unsupported_recipe_variant", "Native recipe rejected its available template candidates");
        ItemStack output = recipe.assemble(input, player.registryAccess());
        if (output.isEmpty()) throw ServerAccess.denied("unsupported_recipe", "Native recipe produced no validated template output");
        return (ItemStack) NativeApi.call(null, "appeng.api.crafting.PatternDetailsHelper", "encodeCraftingPattern",
                holder, grid, output, false, false);
    }

    private static ItemStack candidate(ServerPlayer player, Ingredient ingredient) {
        ItemStack[] displayed = ingredient.getItems();
        if (displayed.length > 128) throw ServerAccess.denied("recipe_limit", "Ingredient alternatives exceed the bounded native template search");
        ItemStack selected = ItemStack.EMPTY; String selectedKey = null;
        for (ItemStack sample : displayed) {
            if (sample.isEmpty() || !ingredient.test(sample)) continue;
            String key = ResourceIdentity.key(ResourceIdentity.item(sample, player.registryAccess()));
            if (selectedKey == null || key.compareTo(selectedKey) < 0) { selected = sample; selectedKey = key; }
        }
        if (selected.isEmpty()) throw ServerAccess.denied("unsupported_recipe_variant", "Native ingredient exposes no matching concrete candidate");
        return selected.copyWithCount(1);
    }
}
