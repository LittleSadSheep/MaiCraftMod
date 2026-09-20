// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;

/** 每条方案共用一份库存：原料不能再当燃料，合成余料可继续使用，同组替代材料可以混合供给。 */
public final class CookingStockBudgetTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var previousTags = BuiltInRegistries.ITEM.getTags().collect(Collectors.toMap(
                pair -> pair.getFirst(), pair -> pair.getSecond().stream().toList()));
        try (var world = new CookingTestWorld()) {
            BuiltInRegistries.ITEM.bindTags(Map.of(ItemTags.LOGS, List.of(Items.OAK_LOG.builtInRegistryHolder())));
            // 测试直接替换标签时，也模拟原生标签更新对燃料缓存的失效，避免继承前一场景的空木材表。
            AbstractFurnaceBlockEntity.invalidateCache();
            world.inventory(0, 0, 1);
            world.game.inventory.setItem(1, new ItemStack(Items.OAK_LOG));
            var charcoal = cooking(Items.OAK_LOG, Items.CHARCOAL);
            world.recipes(List.of(new RecipeHolder<>(charcoal.recipeId(), charcoal.recipe())));
            var request = new SemanticCookTaskRecord("shared-log", 1000, CookingTestWorld.id("charcoal"), 1,
                    SemanticCookTaskRecord.Preference.AUTO, List.of(CookingTestWorld.id("coal"), CookingTestWorld.id("oak_log")),
                    List.of(Source.INVENTORY), false, List.of());
            var task = new SemanticCookCompanionTask(world.game.player, request);
            task.start(world.game.player);
            task.tick(world.game.player);
            check(CookingTestWorld.read(task, "fuel") == Items.COAL, "必须留住唯一原木做原料，不能因燃烧浪费少就把它选作燃料");

            world.inventory(0, 0, 1);
            world.recipes(List.of(craft(Items.RAW_IRON, 1, Ingredient.of(Items.COAL))));
            var recursive = planner(world);
            check(recursive.fuelChoice(cooking(Items.RAW_IRON, Items.IRON_INGOT), Items.COAL, 1)
                    .preparationCost() >= CookingRecipePlanner.UNAVAILABLE_COST, "合成原料已经用掉的煤不能再烧一次");

            world.inventory(0, 0, 0);
            world.game.inventory.setItem(0, new ItemStack(Items.OAK_PLANKS));
            world.recipes(List.of(craft(Items.STICK, 4, Ingredient.of(Items.OAK_PLANKS))));
            var leftovers = planner(world).fuelChoice(cooking(Items.STICK, Items.IRON_INGOT), Items.STICK, 1);
            check(leftovers.preparationCost() == 250, "四根木棍中一根做原料、两根做燃料，只需合成一次");

            world.inventory(0, 0, 1);
            world.game.inventory.setItem(1, new ItemStack(Items.OAK_PLANKS));
            world.game.inventory.setItem(2, new ItemStack(Items.BIRCH_PLANKS));
            var either = Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS);
            world.recipes(List.of(craft(Items.RAW_IRON, 1, either, either)));
            check(planner(world).fuelChoice(cooking(Items.RAW_IRON, Items.IRON_INGOT), Items.COAL, 1)
                    .preparationCost() == 250, "同组可替代材料应合计现货，不必每种都凑够两块");
            world.inventory(0, 0, 1);
            for (Source source : List.of(Source.NEARBY, Source.STORAGE, Source.TRADE)) {
                var sourceRequest = new SemanticCookTaskRecord("source-estimate", 1000,
                        CookingTestWorld.id("iron_ingot"), 1, SemanticCookTaskRecord.Preference.AUTO,
                        List.of(), List.of(source), false, List.of());
                var permitted = new CookingRecipePlanner(world.game.player, sourceRequest);
                permitted.observeNearbyBlocks();
                check(permitted.fuelChoice(cooking(Items.RAW_IRON, Items.IRON_INGOT), Items.COAL, 1)
                        .preparationCost() < CookingRecipePlanner.UNAVAILABLE_COST,
                        "已允许的 " + source + " 应由真实取物任务核实，不能被不完整的内置来源表提前排除");
            }
            check(world.game.blockUses() == 0 && world.game.itemUses() == 0, "估价不得预支真实物品");
        } finally {
            BuiltInRegistries.ITEM.bindTags(previousTags);
            AbstractFurnaceBlockEntity.invalidateCache();
        }
        System.out.println("CookingStockBudgetTest: passed");
    }

    private static CookingRecipePlanner planner(CookingTestWorld world) {
        var request = new SemanticCookTaskRecord("stock-budget", 1000, CookingTestWorld.id("iron_ingot"), 1,
                SemanticCookTaskRecord.Preference.AUTO, List.of(), List.of(Source.INVENTORY, Source.CRAFT), false, List.of());
        var planner = new CookingRecipePlanner(world.game.player, request);
        planner.observeNearbyBlocks();
        return planner;
    }

    private static CookingRecipe cooking(Item input, Item output) {
        var recipe = new SmeltingRecipe("", CookingBookCategory.MISC, Ingredient.of(input), new ItemStack(output), 0, 200);
        return new CookingRecipe(CookingTestWorld.id("stock_test"), recipe, CookingDevice.FURNACE, input, 1);
    }

    private static RecipeHolder<?> craft(Item output, int count, Ingredient... inputs) {
        return new RecipeHolder<>(CookingTestWorld.id("material_test"), new ShapelessRecipe("", CraftingBookCategory.MISC,
                new ItemStack(output, count), NonNullList.of(Ingredient.EMPTY, inputs)));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
