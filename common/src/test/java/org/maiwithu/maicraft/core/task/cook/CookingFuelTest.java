// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;

/** 高炉和烟熏炉更快，也更快烧完同一份燃料；加工十六份原料仍各需两块煤。 */
public final class CookingFuelTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            world.inventory.setItem(0, new ItemStack(Items.COAL, 64));
            var record = new SemanticCookTaskRecord("fuel-test", 1000, id("iron_ingot"), 16,
                    SemanticCookTaskRecord.Preference.AUTO, List.of(id("coal")), List.of(Source.INVENTORY), false, List.of());
            var planner = new CookingRecipePlanner(world.player, record);
            verify(planner, "FURNACE", new SmeltingRecipe("", CookingBookCategory.MISC,
                    Ingredient.of(Items.RAW_IRON), new ItemStack(Items.IRON_INGOT), 0, 200), 1600);
            verify(planner, "BLAST_FURNACE", new BlastingRecipe("", CookingBookCategory.MISC,
                    Ingredient.of(Items.RAW_IRON), new ItemStack(Items.IRON_INGOT), 0, 100), 800);
            verify(planner, "SMOKER", new SmokingRecipe("", CookingBookCategory.FOOD,
                    Ingredient.of(Items.BEEF), new ItemStack(Items.COOKED_BEEF), 0, 100), 800);
            check(world.blockUses() == 0 && world.itemUses() == 0, "估算燃料不能操作世界或消耗背包");
        }
        System.out.println("CookingFuelTest: passed");
    }

    private static void verify(CookingRecipePlanner planner, String deviceName,
                               AbstractCookingRecipe recipe, int expectedDuration) throws Exception {
        var candidate = new CookingRecipe(id("test_recipe"), recipe, CookingDevice.valueOf(deviceName),
                recipe.getIngredients().getFirst().getItems()[0].getItem(), 1);
        var selected = planner.fuelChoice(candidate, Items.COAL, 16);
        check(selected.burnTicks() == expectedDuration, deviceName + " 没有使用该炉子的燃烧时长");
        check(selected.count() == 2, deviceName + " 没有为十六份原料准备两块煤");
    }

    private static ResourceLocation id(String path) { return ResourceLocation.withDefaultNamespace(path); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
