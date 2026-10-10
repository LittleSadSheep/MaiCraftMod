// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.List;

/**
 * 一条工作站配方：角色能自己在合成台、熔炉、石切台上动手做的，从游戏真实配方管理器里读出来的只读快照。含模组配方；
 * 原料写的是游戏要的东西（具体物品或标签），产出写一次能做出几件。
 *
 * @param id           配方的注册 ID，例如 minecraft:iron_ingot_from_blasting_iron_ore
 * @param kind         在哪种设施上做（合成台、熔炉、石切台）
 * @param result       做出来的东西
 * @param resultCount  做一次出几件
 * @param ingredients  做一次要的原料；烧炼与石切台只有一条
 * @param fitsInInventory 合成配方摆得进背包的 2×2 合成格（木板、木棍、工作台这类）：不用工作台，打开背包就能做
 */
public record WorkstationRecipe(String id, Kind kind, WantedItem result, int resultCount,
        List<IngredientStack> ingredients, boolean fitsInInventory) {

    /** 不看摆不摆得进背包合成格的配方：烧炼、石切台，以及只能在工作台上做的合成。 */
    public WorkstationRecipe(String id, Kind kind, WantedItem result, int resultCount,
            List<IngredientStack> ingredients) {
        this(id, kind, result, resultCount, ingredients, false);
    }

    public WorkstationRecipe {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("配方必须有注册 ID");
        if (kind == null) throw new IllegalArgumentException("配方必须有设施种类");
        if (result == null) throw new IllegalArgumentException("配方必须有产出");
        if (resultCount < 1) throw new IllegalArgumentException("配方的产出数量至少为 1：" + resultCount);
        ingredients = List.copyOf(ingredients);
        if (ingredients.isEmpty()) throw new IllegalArgumentException("配方至少要有一种原料：" + id);
        if (fitsInInventory && kind != Kind.CRAFTING) {
            throw new IllegalArgumentException("只有合成配方能在背包的合成格里做：" + id);
        }
    }

    /** 做这条配方的设施。 */
    public enum Kind {
        /** 合成：摆得进 2×2 的在背包合成格里做，摆不进的上工作台（见 fitsInInventory）。 */
        CRAFTING,
        /** 熔炉烧炼；除了原料还要燃料。 */
        SMELTING,
        /** 石切台切割，机制与合成同途径：读配方、放原料、取成品。 */
        STONECUTTING
    }

    /** 做一次要的一种原料：要什么、要几件。 */
    public record IngredientStack(WantedItem item, int count) {
        public IngredientStack {
            if (item == null) throw new IllegalArgumentException("原料必须写要什么");
            if (count < 1) throw new IllegalArgumentException("原料数量至少为 1：" + count);
        }
    }
}
