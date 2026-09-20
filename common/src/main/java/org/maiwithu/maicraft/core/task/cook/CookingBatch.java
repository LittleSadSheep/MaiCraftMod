// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.core.tools.RecipeProbe;

/** 一炉要装多少原料和燃料；总目标可以很大，但这一次必须装得进实际物品的堆叠上限。 */
record CookingBatch(int inputCount, int fuelCount, int burnTicks) {
    static CookingBatch plan(CookingRecipe cooking, Item fuel, int missingInputs, HolderLookup.Provider registries) {
        ItemStack input = new ItemStack(cooking.input());
        ItemStack output = RecipeProbe.resultOf(cooking.recipe(), registries);
        ItemStack fuelStack = new ItemStack(fuel);
        int cookTicks = cooking.recipe().getCookingTime();
        int burnTicks = cooking.device().burnDuration(fuelStack);
        if (missingInputs <= 0 || cookTicks <= 0 || burnTicks <= 0 || output.isEmpty()) return null;
        // 同时限制原料格、成品格和燃料格；例如只能叠十六个的成品、不能叠放的熔岩桶，都不能按六十四个算。
        long byFuel = (long) fuelStack.getMaxStackSize() * burnTicks / cookTicks;
        long capacity = Math.min(input.getMaxStackSize(),
                Math.min(output.getMaxStackSize() / cooking.outputCount(), byFuel));
        int raw = (int) Math.min(missingInputs, capacity);
        if (raw <= 0) return null;
        int fuelCount = (int) Math.ceilDiv((long) raw * cookTicks, burnTicks);
        return new CookingBatch(raw, fuelCount, burnTicks);
    }
}
