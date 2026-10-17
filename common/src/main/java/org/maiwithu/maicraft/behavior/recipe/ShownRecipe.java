// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.recipe;

import java.util.List;
import java.util.Objects;

import com.google.gson.JsonObject;

/**
 * 配方查看器里的一条配方：在哪一类、在哪做、要什么、出什么，以及它在游戏里的原始定义。
 *
 * @param recipeId     配方的注册 ID；查看器自己拼出来的展示配方没有 ID，为 null，不编一个
 * @param category     配方类别的 ID，和查看器里的一栏对应，例如 create:mixing
 * @param categoryName 这一栏在当前语言里的名字，例如"搅拌"；读不到时用类别 ID
 * @param workstations 这一类配方在哪做：工作台、熔炉，或一台机器加配套方块；查看器没标时为空
 * @param inputs       要消耗的原料，每格一份
 * @param catalysts    要摆着、但不消耗的东西
 * @param outputs      做出来的东西
 * @param definition   配方在游戏里的原始定义（配方自己的序列化格式转成的 JSON）：加热要求、加工时间、
 *                     带几率的副产物都在这里，原样给，不解释；查看器给不出背后的游戏配方时为 null
 */
public record ShownRecipe(String recipeId, String category, String categoryName, List<ShownStack> workstations,
        List<ShownIngredient> inputs, List<ShownIngredient> catalysts, List<ShownStack> outputs, JsonObject definition) {

    public ShownRecipe {
        if (recipeId != null && recipeId.isBlank()) throw new IllegalArgumentException("配方 ID 要么给全，要么为 null");
        Objects.requireNonNull(category, "category");
        categoryName = categoryName == null || categoryName.isBlank() ? category : categoryName;
        workstations = List.copyOf(workstations);
        inputs = List.copyOf(inputs);
        catalysts = List.copyOf(catalysts);
        outputs = List.copyOf(outputs);
        definition = definition == null ? null : definition.deepCopy();
    }

    /** 原始定义的副本：调用方改了也不影响这条配方。 */
    @Override
    public JsonObject definition() {
        return definition == null ? null : definition.deepCopy();
    }

    /** 做出来的东西里有没有这件物品。 */
    public boolean makes(String itemId) {
        return outputs.stream().anyMatch(stack -> stack.kind() == ShownStack.Kind.ITEM && stack.id().equals(itemId));
    }

    /** 这件物品是不是这条配方的原料或催化剂。 */
    public boolean uses(String itemId) {
        return inputs.stream().anyMatch(ingredient -> ingredient.accepts(itemId))
                || catalysts.stream().anyMatch(ingredient -> ingredient.accepts(itemId));
    }

    /** 这件物品是不是这一类配方的工作站。 */
    public boolean madeAt(String itemId) {
        return workstations.stream().anyMatch(stack -> stack.kind() == ShownStack.Kind.ITEM && stack.id().equals(itemId));
    }
}
