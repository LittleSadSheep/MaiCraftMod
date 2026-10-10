// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.recipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * 游戏配方表当作最后一个配方查看器：没装 EMI、JEI，或它们这次都答不了时由它回答。
 *
 * <p>配方表是服务器同步给客户端的全部配方，模组的加工配方也在里面，原料、主产出、原始定义都读得到。
 * 读不到的是"这类配方在哪台机器上做"（工作站只有配方查看器标得出来），以及查看器自己拼出来的展示配方；
 * 所以按工作站查一律答空，配方的工作站列表也为空。产出只认配方报出来的主产出，副产物在原始定义里。
 */
public final class GameRecipeTable implements RecipeViewer {
    /** 结果里写"从哪读的"用的名字。 */
    public static final String NAME = "game";

    private final Supplier<Optional<GameRecipes>> recipes;

    public GameRecipeTable(Supplier<Optional<GameRecipes>> recipes) {
        this.recipes = Objects.requireNonNull(recipes, "recipes");
    }

    @Override public String name() {
        return NAME;
    }

    @Override public boolean importsOtherViewers() {
        return false;
    }

    @Override public Readiness readiness() {
        return recipes.get().isPresent() ? Readiness.yes() : Readiness.no("角色不在世界里，读不到游戏配方表");
    }

    // 主产出是这件物品的配方：配方报出来的主产出（getResultItem）对上物品 ID 才算。
    @Override public List<ShownRecipe> making(String itemId) {
        Optional<GameRecipes> table = recipes.get();
        if (table.isEmpty()) return List.of();
        List<ShownRecipe> found = new ArrayList<>();
        for (RecipeHolder<?> holder : table.get().all()) {
            ItemStack result = holder.value().getResultItem(table.get().registries());
            if (result != null && !result.isEmpty() && itemId(result).equals(itemId)) {
                found.add(describe(holder, table.get()));
            }
        }
        return List.copyOf(found);
    }

    // 拿它当原料的配方：任何一格原料接受这件物品就算。物品 ID 不在注册表里的没有用途，不猜。
    @Override public List<ShownRecipe> using(String itemId) {
        Optional<GameRecipes> table = recipes.get();
        Optional<Item> item = item(itemId);
        if (table.isEmpty() || item.isEmpty()) return List.of();
        ItemStack sample = new ItemStack(item.get());
        List<ShownRecipe> found = new ArrayList<>();
        for (RecipeHolder<?> holder : table.get().all()) {
            if (holder.value().getIngredients().stream().anyMatch(ingredient -> !ingredient.isEmpty() && ingredient.test(sample))) {
                found.add(describe(holder, table.get()));
            }
        }
        return List.copyOf(found);
    }

    // 游戏配方表不知道哪类配方在哪做，按工作站查没有答案；配方查询会在说明里讲清这一点。
    @Override public List<ShownRecipe> atWorkstation(String itemId) {
        return List.of();
    }

    // 一条配方读成查看器的样子：类别就是配方类型，原料按格合并计数，产出只有主产出，原始定义原样带上。
    private static ShownRecipe describe(RecipeHolder<?> holder, GameRecipes table) {
        Recipe<?> recipe = holder.value();
        ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        String category = type == null ? "unknown" : type.toString();
        ItemStack result = recipe.getResultItem(table.registries());
        List<ShownStack> outputs = result == null || result.isEmpty() ? List.of() : List.of(stack(result));
        return new ShownRecipe(holder.id().toString(), category, category, List.of(), inputs(recipe.getIngredients(), table),
                List.of(), outputs, definition(recipe, table));
    }

    // 每格一份原料；写法完全相同的几格合成一份并计数（三格木板是"木板 ×3"）。空格不算原料。
    private static List<ShownIngredient> inputs(List<Ingredient> ingredients, GameRecipes table) {
        Map<String, Ingredient> distinct = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            String key = encode(ingredient, table).map(JsonElement::toString).orElseGet(() -> itemsKey(ingredient));
            distinct.putIfAbsent(key, ingredient);
            counts.merge(key, 1, Integer::sum);
        }
        List<ShownIngredient> shown = new ArrayList<>();
        for (var entry : distinct.entrySet()) {
            Ingredient ingredient = entry.getValue();
            List<ShownStack> options = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (ItemStack option : ingredient.getItems()) {
                if (seen.add(itemId(option))) options.add(ShownStack.item(itemId(option), option.getHoverName().getString(), 1));
            }
            String tag = tagOf(ingredient, table);
            if (options.isEmpty() && tag == null) continue;
            shown.add(new ShownIngredient(tag, options, counts.get(entry.getKey())));
        }
        return List.copyOf(shown);
    }

    // 配方本来写的是标签时（编码出来是 {"tag": …}）照写标签；写成几样具体物品的不硬凑标签。
    private static String tagOf(Ingredient ingredient, GameRecipes table) {
        return encode(ingredient, table)
                .filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
                .filter(json -> json.size() == 1 && json.has("tag") && json.get("tag").isJsonPrimitive())
                .map(json -> json.get("tag").getAsString())
                .orElse(null);
    }

    private static Optional<JsonElement> encode(Ingredient ingredient, GameRecipes table) {
        return Ingredient.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, table.registries()), ingredient).result();
    }

    private static String itemsKey(Ingredient ingredient) {
        List<String> ids = new ArrayList<>();
        for (ItemStack option : ingredient.getItems()) ids.add(itemId(option));
        return String.join(",", ids);
    }

    // 配方自己的序列化格式转成 JSON：加热要求、加工时间、带几率的副产物都在里面；转不出来就不给，不编。
    private static JsonObject definition(Recipe<?> recipe, GameRecipes table) {
        return RecipeDefinitions.of(recipe, table.registries());
    }

    private static ShownStack stack(ItemStack stack) {
        return ShownStack.item(itemId(stack), stack.getHoverName().getString(), stack.getCount());
    }

    private static Optional<Item> item(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId.toLowerCase(Locale.ROOT));
        return id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
