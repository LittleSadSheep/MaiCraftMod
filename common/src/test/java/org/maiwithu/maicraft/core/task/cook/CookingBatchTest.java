// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.SmeltingRecipe;

/** 按真实物品堆叠上限分炉，不把“最终库存很多”误解释为一次往槽里塞很多。 */
public final class CookingBatchTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        expect(Items.RAW_IRON, Items.IRON_INGOT, 1, 200, Items.COAL, 300, 64, 8);
        expect(Items.RAW_IRON, Items.IRON_INGOT, 3, 200, Items.COAL, 300, 21, 3);
        expect(Items.ENDER_PEARL, Items.IRON_INGOT, 1, 200, Items.COAL, 300, 16, 2);
        expect(Items.RAW_IRON, Items.ENDER_PEARL, 1, 200, Items.COAL, 300, 16, 2);
        expect(Items.RAW_IRON, Items.IRON_INGOT, 1, 1000, Items.LAVA_BUCKET, 300, 20, 1);
        expect(Items.RAW_IRON, Items.IRON_INGOT, 1, 200, Items.COAL, 5, 5, 1);
        check(plan(Items.RAW_IRON, Items.IRON_INGOT, 65, 200, Items.COAL, 1) == null,
                "单份产物都装不进结果槽时不能强行开炉");
        check(plan(Items.RAW_IRON, Items.IRON_INGOT, 1, 0, Items.COAL, 1) == null,
                "无有效加工时间的配方不能参与燃料除法");
        System.out.println("CookingBatchTest: passed");
    }

    private static void expect(Item input, Item output, int outputCount, int ticks, Item fuel, int missing,
                               int expectedRaw, int expectedFuel) {
        var batch = plan(input, output, outputCount, ticks, fuel, missing);
        check(batch != null && batch.inputCount() == expectedRaw && batch.fuelCount() == expectedFuel,
                "批次超出槽位容量或改变了应备数量: " + batch);
    }

    private static CookingBatch plan(Item input, Item output, int outputCount, int ticks, Item fuel, int missing) {
        var recipe = new SmeltingRecipe("", CookingBookCategory.MISC, Ingredient.of(input),
                new ItemStack(output, outputCount), 0, ticks);
        return CookingBatch.plan(new CookingRecipe(ResourceLocation.withDefaultNamespace("test_batch"), recipe,
                CookingDevice.FURNACE, input, outputCount), fuel, missing, RegistryAccess.EMPTY);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
