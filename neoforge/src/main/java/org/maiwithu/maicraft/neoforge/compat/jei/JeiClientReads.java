// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.jei;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

import org.maiwithu.maicraft.behavior.recipe.RecipeDefinitions;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;
import org.maiwithu.maicraft.compat.jei.JeiReads;

/**
 * JEI 的读写端：直接调用 JEI 的公开接口，把它显示的配方原样翻成项目的配方记录；只翻译，不判断。
 *
 * <p>按物品查用 JEI 的焦点查找（做出它 = 产出焦点，用到它 = 原料焦点），不带被隐藏的类别与配方；
 * 按工作站查找它是哪几类的催化剂，再取这几类的配方。每个格子的候选从配方布局里读（和界面上画的一样），
 * 格子不带标签；工作站就是 JEI 的催化剂。背后是游戏配方的，用那条配方的 ID 与原始定义。
 */
public final class JeiClientReads implements JeiReads {

    @Override public boolean runtimeReady() {
        return JeiRuntimeHandoff.current() != null;
    }

    @Override public List<ShownRecipe> making(String itemId) {
        return focused(itemId, RecipeIngredientRole.OUTPUT);
    }

    @Override public List<ShownRecipe> using(String itemId) {
        return focused(itemId, RecipeIngredientRole.INPUT);
    }

    @Override public List<ShownRecipe> atWorkstation(String itemId) {
        IJeiRuntime runtime = runtime();
        IRecipeManager manager = runtime.getRecipeManager();
        List<ShownRecipe> shown = new ArrayList<>();
        for (IRecipeCategory<?> category : manager.createRecipeCategoryLookup().get().toList()) {
            boolean station = manager.createRecipeCatalystLookup(category.getRecipeType()).get()
                    .anyMatch(typed -> typed.getItemStack().map(stack -> itemId(stack).equals(itemId)).orElse(false));
            if (station) addAll(runtime, category, null, shown);
        }
        return List.copyOf(shown);
    }

    private List<ShownRecipe> focused(String itemId, RecipeIngredientRole role) {
        Optional<ItemStack> item = item(itemId);
        if (item.isEmpty()) return List.of();
        IJeiRuntime runtime = runtime();
        IFocus<ItemStack> focus = runtime.getJeiHelpers().getFocusFactory().createFocus(role, VanillaTypes.ITEM_STACK, item.get());
        List<ShownRecipe> shown = new ArrayList<>();
        for (IRecipeCategory<?> category : runtime.getRecipeManager().createRecipeCategoryLookup()
                .limitFocus(List.of(focus)).get().toList()) {
            addAll(runtime, category, focus, shown);
        }
        return List.copyOf(shown);
    }

    // 一个类别里（有焦点时只取焦点对得上的）的全部配方，逐条翻成配方记录。
    private <T> void addAll(IJeiRuntime runtime, IRecipeCategory<T> category, IFocus<ItemStack> focus, List<ShownRecipe> shown) {
        IRecipeManager manager = runtime.getRecipeManager();
        var lookup = manager.createRecipeLookup(category.getRecipeType());
        if (focus != null) lookup = lookup.limitFocus(List.of(focus));
        IFocusGroup noFocus = runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
        List<ShownStack> stations = manager.createRecipeCatalystLookup(category.getRecipeType()).get()
                .map(typed -> stack(runtime, typed)).flatMap(Optional::stream).toList();
        for (T recipe : lookup.get().toList()) {
            Optional<IRecipeLayoutDrawable<T>> layout = manager.createRecipeLayoutDrawable(category, recipe, noFocus);
            if (layout.isEmpty()) continue;
            var slots = layout.get().getRecipeSlotsView();
            shown.add(new ShownRecipe(recipeId(category, recipe), category.getRecipeType().getUid().toString(),
                    category.getTitle().getString(), stations,
                    ingredients(runtime, slots.getSlotViews(RecipeIngredientRole.INPUT)),
                    ingredients(runtime, slots.getSlotViews(RecipeIngredientRole.CATALYST)),
                    outputs(runtime, slots.getSlotViews(RecipeIngredientRole.OUTPUT)), definition(recipe)));
        }
    }

    private static <T> String recipeId(IRecipeCategory<T> category, T recipe) {
        if (recipe instanceof RecipeHolder<?> holder) return holder.id().toString();
        ResourceLocation id = category.getRegistryName(recipe);
        return id == null ? null : id.toString();
    }

    private static JsonObject definition(Object recipe) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(recipe instanceof RecipeHolder<?> holder) || minecraft.level == null) return null;
        return RecipeDefinitions.of(holder.value(), minecraft.level.registryAccess());
    }

    // 每个原料格一份：格子里能放的全部候选；数量取候选自己的（物品件数、流体毫桶）。
    private static List<ShownIngredient> ingredients(IJeiRuntime runtime, List<IRecipeSlotView> slots) {
        List<ShownIngredient> shown = new ArrayList<>();
        for (IRecipeSlotView slot : slots) {
            List<ShownStack> options = new ArrayList<>();
            for (ITypedIngredient<?> typed : slot.getAllIngredientsList()) {
                stack(runtime, typed).ifPresent(options::add);
            }
            if (options.isEmpty()) continue;
            shown.add(new ShownIngredient(null, distinct(options), Math.max(1, options.getFirst().amount())));
        }
        return List.copyOf(shown);
    }

    // 产出格一格出一样：界面上显示的那一样。
    private static List<ShownStack> outputs(IJeiRuntime runtime, List<IRecipeSlotView> slots) {
        List<ShownStack> shown = new ArrayList<>();
        for (IRecipeSlotView slot : slots) {
            slot.getDisplayedIngredient().or(() -> slot.getAllIngredients().findFirst())
                    .flatMap(typed -> stack(runtime, typed)).ifPresent(shown::add);
        }
        return List.copyOf(shown);
    }

    private static List<ShownStack> distinct(List<ShownStack> options) {
        List<ShownStack> kept = new ArrayList<>();
        for (ShownStack option : options) {
            if (kept.stream().noneMatch(seen -> seen.kind() == option.kind() && seen.id().equals(option.id()))) kept.add(option);
        }
        return kept;
    }

    // 物品写件数，流体写毫桶，别的照 JEI 的数；名字用 JEI 显示的名字。JEI 的格子不标几率。
    private static <V> Optional<ShownStack> stack(IJeiRuntime runtime, ITypedIngredient<V> typed) {
        V ingredient = typed.getIngredient();
        IIngredientHelper<V> helper = runtime.getIngredientManager().getIngredientHelper(typed.getType());
        ResourceLocation id = helper.getResourceLocation(ingredient);
        if (id == null) return Optional.empty();
        ShownStack.Kind kind = typed.getType() == VanillaTypes.ITEM_STACK ? ShownStack.Kind.ITEM
                : typed.getType() == NeoForgeTypes.FLUID_STACK ? ShownStack.Kind.FLUID : ShownStack.Kind.OTHER;
        return Optional.of(new ShownStack(kind, id.toString(), helper.getDisplayName(ingredient),
                Math.max(0, helper.getAmount(ingredient)), 1));
    }

    private static IJeiRuntime runtime() {
        IJeiRuntime runtime = JeiRuntimeHandoff.current();
        if (runtime == null) throw new IllegalStateException("JEI 的运行时还没交出来");
        return runtime;
    }

    private static Optional<ItemStack> item(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        return id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id).map(ItemStack::new);
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
