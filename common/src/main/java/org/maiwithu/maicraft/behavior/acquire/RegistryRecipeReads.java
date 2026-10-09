// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 配方与燃料读端的注册表实现：能做出一种东西的有哪些做法、一件东西能烧多久，
 * 都从角色所在世界真实的配方管理器与燃料表读（服务端同步给客户端的数据，含模组配方）。
 *
 * <p>配方按设施种类分开问：合成一条（含石切台）、烧炼一条，via 参数认的就是这个分法。
 * 产出写具体物品；原料优先写成标签（配方的原料本来就是"这一类都行"），
 * 对不上任何标签才落成具体物品；注册表里查不到的物品如实说没有做法，不猜。
 */
public final class RegistryRecipeReads implements ReadsRecipes, ReadsFuels {

    private final Supplier<PlayerContext> context;
    private final ReadsItemTags tags;

    public RegistryRecipeReads(Supplier<PlayerContext> context, ReadsItemTags tags) {
        this.context = Objects.requireNonNull(context, "context");
        this.tags = Objects.requireNonNull(tags, "tags");
    }

    @Override
    public List<RecipeView> recipesProducing(WantedItem wanted) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) {
            return List.of();
        }
        List<RecipeView> views = new ArrayList<>();
        collect(current, RecipeType.CRAFTING, RecipeView.Kind.CRAFTING, wanted, views);
        collect(current, RecipeType.SMELTING, RecipeView.Kind.SMELTING, wanted, views);
        collect(current, RecipeType.STONECUTTING, RecipeView.Kind.STONECUTTING, wanted, views);
        return List.copyOf(views);
    }

    @Override
    public int burnTicks(String itemId) {
        var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(itemId.toLowerCase(Locale.ROOT)));
        // 燃料表按原版与数据包注册的燃料回答；烧不起来的东西就是 0 刻。
        return item.map(value -> AbstractFurnaceBlockEntity.getFuel().getOrDefault(value, 0)).orElse(0);
    }

    // 按设施种类收配方：产出对上想要的（具体物品按 ID，标签按物品挂的标签）才算一条做法。
    private <I extends RecipeInput, T extends Recipe<I>> void collect(PlayerContext current, RecipeType<T> type, RecipeView.Kind kind,
            WantedItem wanted, List<RecipeView> views) {
        for (RecipeHolder<?> holder : current.level().getRecipeManager().getAllRecipesFor(type)) {
            var result = holder.value().getResultItem(current.level().registryAccess());
            if (result == null || result.isEmpty()) {
                continue;
            }
            String resultId = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
            if (!wanted.matches(resultId, tags.tagsOf(resultId))) {
                continue;
            }
            views.add(new RecipeView(holder.id().toString(), kind,
                    WantedItem.ofItem(resultId), result.getCount(), ingredients(holder.value().getIngredients())));
        }
    }

    // 一条配方的原料清单：每格一份，同一种写法合并计数。
    private List<RecipeView.IngredientStack> ingredients(List<Ingredient> ingredients) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (Ingredient ingredient : ingredients) {
            specifierOf(ingredient).ifPresent(specifier -> merged.merge(specifier, 1, Integer::sum));
        }
        List<RecipeView.IngredientStack> stacks = new ArrayList<>();
        for (var entry : merged.entrySet()) {
            String specifier = entry.getKey();
            stacks.add(new RecipeView.IngredientStack(
                    specifier.startsWith("#")
                            ? WantedItem.ofTag(specifier.substring(1))
                            : WantedItem.ofItem(specifier),
                    entry.getValue()));
        }
        return List.copyOf(stacks);
    }

    // 一格原料想要什么：配方的原料多半是"这一类都行"。能对上一张装的物品与这一格完全相同的物品标签
    // 就写成标签；对不上才落成第一种具体物品，这一格照样计数，不能因为没有标签就少算一份原料。
    // 一格什么都不要的不算原料。
    private static Optional<String> specifierOf(Ingredient ingredient) {
        ItemStack[] items = ingredient.getItems();
        if (items.length == 0) {
            return Optional.empty();
        }
        if (items.length == 1) {
            return Optional.of(itemId(items[0]));
        }
        return Optional.of(exactTag(items).map(tag -> "#" + tag.location()).orElse(itemId(items[0])));
    }

    // 找装的物品与这一格接受的完全相同的标签：只认相同，不认更大的——更大的标签里有配方不收的东西，
    // 照它备料会去弄一样放不进合成格的物品。
    private static Optional<TagKey<Item>> exactTag(ItemStack[] items) {
        var wanted = new HashSet<String>();
        for (ItemStack stack : items) {
            wanted.add(itemId(stack));
        }
        for (var tag : BuiltInRegistries.ITEM.getTagNames().toList()) {
            var contents = BuiltInRegistries.ITEM.getTag(tag).orElse(null);
            if (contents == null || contents.size() != wanted.size()) continue;
            boolean same = contents.stream()
                    .allMatch(holder -> wanted.contains(BuiltInRegistries.ITEM.getKey(holder.value()).toString()));
            if (same) {
                return Optional.of(tag);
            }
        }
        return Optional.empty();
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
