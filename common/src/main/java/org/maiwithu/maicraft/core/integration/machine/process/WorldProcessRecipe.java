// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.FluidState;

/** 世界内加工配方的只读规则：先按原生原料谓词备料，再把触发物最后投入匹配环境，最终核对实际产物。 */
public interface WorldProcessRecipe {
    ResourceLocation id();
    List<Ingredient> inputs();
    ItemStack result();
    boolean supports(FluidState fluid);
    boolean isFluid();
    JsonObject describe();

    /** 适配器给出哪一项原料触发加工；执行器据此最后投放该项，避免其他原料尚未送齐就开始反应。 */
    int triggerInputIndex();

    /** 原生机制以触发物底部位置为中心搜集原料的半径；未知时不能假定整个水池里的原料都会参与反应。 */
    default java.util.OptionalDouble inputSearchRadius() { return java.util.OptionalDouble.empty(); }
}
