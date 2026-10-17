// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.emi;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonObject;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.TagEmiIngredient;
import dev.emi.emi.runtime.EmiReloadManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.material.Fluid;

import org.maiwithu.maicraft.behavior.recipe.RecipeDefinitions;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;
import org.maiwithu.maicraft.compat.emi.EmiReads;

/**
 * EMI 的读写端：直接调用 EMI 的公开接口，把它显示的配方原样翻成项目的配方记录；只翻译，不判断。
 *
 * <p>按物品查用 EMI 自己建好的索引（getRecipesByOutput / getRecipesByInput），不遍历全部配方；
 * 按工作站查先找它是哪几类的工作站，再取这几类的配方。加载完没有看 EmiReloadManager.isLoaded()（公开类，不在 api 包里）。
 * EMI 自己拼的展示配方的 ID 以斜杠开头，不是真的配方 ID，翻成 null；背后有游戏配方的用那条配方的 ID 和原始定义。
 */
public final class EmiClientReads implements EmiReads {

    @Override public boolean loaded() {
        return EmiReloadManager.isLoaded();
    }

    @Override public List<ShownRecipe> making(String itemId) {
        return item(itemId).map(stack -> shown(EmiApi.getRecipeManager().getRecipesByOutput(EmiStack.of(stack)))).orElse(List.of());
    }

    @Override public List<ShownRecipe> using(String itemId) {
        return item(itemId).map(stack -> shown(EmiApi.getRecipeManager().getRecipesByInput(EmiStack.of(stack)))).orElse(List.of());
    }

    @Override public List<ShownRecipe> atWorkstation(String itemId) {
        EmiRecipeManager manager = EmiApi.getRecipeManager();
        List<EmiRecipe> recipes = new ArrayList<>();
        for (EmiRecipeCategory category : manager.getCategories()) {
            boolean station = manager.getWorkstations(category).stream()
                    .flatMap(ingredient -> ingredient.getEmiStacks().stream())
                    .anyMatch(stack -> stack.getKey() instanceof Item && stack.getId().toString().equals(itemId));
            if (station) recipes.addAll(manager.getRecipes(category));
        }
        return shown(recipes);
    }

    private List<ShownRecipe> shown(List<EmiRecipe> recipes) {
        EmiRecipeManager manager = EmiApi.getRecipeManager();
        Map<EmiRecipeCategory, List<ShownStack>> workstations = new IdentityHashMap<>();
        List<ShownRecipe> shown = new ArrayList<>();
        for (EmiRecipe recipe : recipes) {
            EmiRecipeCategory category = recipe.getCategory();
            List<ShownStack> stations = workstations.computeIfAbsent(category, key -> manager.getWorkstations(key).stream()
                    .map(ingredient -> ingredient.getEmiStacks().stream().filter(stack -> !stack.isEmpty()).findFirst())
                    .flatMap(Optional::stream).map(EmiClientReads::stack).toList());
            RecipeHolder<?> backing = recipe.getBackingRecipe();
            shown.add(new ShownRecipe(recipeId(recipe, backing), category.getId().toString(), category.getName().getString(),
                    stations, ingredients(recipe.getInputs()), ingredients(recipe.getCatalysts()),
                    recipe.getOutputs().stream().filter(stack -> !stack.isEmpty()).map(EmiClientReads::stack).toList(),
                    definition(backing)));
        }
        return List.copyOf(shown);
    }

    private static String recipeId(EmiRecipe recipe, RecipeHolder<?> backing) {
        if (backing != null) return backing.id().toString();
        ResourceLocation id = recipe.getId();
        return id == null || id.getPath().startsWith("/") ? null : id.toString();
    }

    private static JsonObject definition(RecipeHolder<?> backing) {
        Minecraft minecraft = Minecraft.getInstance();
        if (backing == null || minecraft.level == null) return null;
        return RecipeDefinitions.of(backing.value(), minecraft.level.registryAccess());
    }

    private static List<ShownIngredient> ingredients(List<EmiIngredient> ingredients) {
        List<ShownIngredient> shown = new ArrayList<>();
        for (EmiIngredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            String tag = ingredient instanceof TagEmiIngredient tagged ? tagged.key.location().toString() : null;
            List<ShownStack> options = ingredient.getEmiStacks().stream().filter(stack -> !stack.isEmpty())
                    .map(EmiClientReads::stack).toList();
            if (options.isEmpty() && tag == null) continue;
            shown.add(new ShownIngredient(tag, options, Math.max(1, ingredient.getAmount())));
        }
        return List.copyOf(shown);
    }

    private static ShownStack stack(EmiStack stack) {
        Object key = stack.getKey();
        ShownStack.Kind kind = key instanceof Item ? ShownStack.Kind.ITEM : key instanceof Fluid ? ShownStack.Kind.FLUID : ShownStack.Kind.OTHER;
        float chance = stack.getChance();
        return new ShownStack(kind, stack.getId().toString(), stack.getName().getString(), Math.max(0, stack.getAmount()),
                chance > 0 && chance <= 1 ? chance : 1);
    }

    private static Optional<ItemStack> item(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        return id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id).map(ItemStack::new);
    }
}
