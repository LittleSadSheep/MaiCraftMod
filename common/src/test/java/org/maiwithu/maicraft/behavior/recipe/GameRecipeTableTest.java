// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 游戏配方表当配方查看器：主产出对上才算做出它、任何一格接受才算用到它、同样的格合并计数、
 * 原始定义原样带上；看不出工作站，角色不在世界里时不能回答。
 */
class GameRecipeTableTest {

    @BeforeAll
    static void 引导注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 主产出对上的才算做出它_同样的格合并计数() {
        GameRecipeTable table = table(torch(), planks(), ironSmelting());

        List<ShownRecipe> torches = table.making("minecraft:torch");

        assertEquals(1, torches.size());
        ShownRecipe torch = torches.get(0);
        assertEquals("test:torch", torch.recipeId());
        assertEquals("minecraft:crafting", torch.category());
        assertEquals(List.of(ShownStack.Kind.ITEM), torch.outputs().stream().map(ShownStack::kind).toList());
        assertEquals(4, torch.outputs().get(0).amount());
        assertEquals(2, torch.inputs().size(), "煤和木棍各一格");
        assertTrue(torch.workstations().isEmpty(), "游戏配方表看不出在哪做");

        ShownRecipe twoLogs = table.making("minecraft:oak_planks").get(0);
        assertEquals(1, twoLogs.inputs().size(), "两格同样的原木合成一份");
        assertEquals(2, twoLogs.inputs().get(0).amount());
    }

    @Test
    void 原始定义原样带上() {
        ShownRecipe smelting = table(ironSmelting()).making("minecraft:iron_ingot").get(0);

        assertNotNull(smelting.definition());
        assertEquals("minecraft:smelting", smelting.definition().get("type").getAsString());
        assertEquals(200, smelting.definition().get("cookingtime").getAsInt());
    }

    @Test
    void 任何一格接受这件物品就算用到它() {
        GameRecipeTable table = table(torch(), planks(), ironSmelting());

        assertEquals(List.of("test:torch"), table.using("minecraft:stick").stream().map(ShownRecipe::recipeId).toList());
        assertTrue(table.using("minecraft:no_such_item").isEmpty(), "不在注册表里的物品没有用途，不猜");
        assertTrue(table.atWorkstation("minecraft:furnace").isEmpty(), "游戏配方表按工作站查一律答空");
    }

    @Test
    void 角色不在世界里时不能回答() {
        GameRecipeTable table = new GameRecipeTable(Optional::empty);

        assertFalse(table.readiness().ready());
        assertTrue(table.making("minecraft:torch").isEmpty());
    }

    private static GameRecipeTable table(RecipeHolder<?>... recipes) {
        GameRecipes snapshot = new GameRecipes(List.of(recipes), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        return new GameRecipeTable(() -> Optional.of(snapshot));
    }

    private static RecipeHolder<?> torch() {
        return new RecipeHolder<>(ResourceLocation.parse("test:torch"), new ShapelessRecipe("", CraftingBookCategory.MISC,
                new ItemStack(Items.TORCH, 4), NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.COAL), Ingredient.of(Items.STICK))));
    }

    private static RecipeHolder<?> planks() {
        return new RecipeHolder<>(ResourceLocation.parse("test:planks"), new ShapelessRecipe("", CraftingBookCategory.MISC,
                new ItemStack(Items.OAK_PLANKS, 8), NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.OAK_LOG), Ingredient.of(Items.OAK_LOG))));
    }

    private static RecipeHolder<?> ironSmelting() {
        return new RecipeHolder<>(ResourceLocation.parse("test:iron"), new SmeltingRecipe("", CookingBookCategory.MISC,
                Ingredient.of(Items.RAW_IRON), new ItemStack(Items.IRON_INGOT), 0.7f, 200));
    }
}
