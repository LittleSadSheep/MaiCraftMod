// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import java.util.List;
import java.util.Objects;

/**
 * 一条"扔进流体里会变成别的东西"的配方：几样料一起泡在同一格水里，过一会儿变成产物
 * （AE2 往水里扔充能赛特斯石英、红石、下界石英变出两个福鲁伊克斯水晶）。只记事实，怎么扔由玩家行为层做。
 *
 * @param id          配方 ID，报价的线索里带着它，动手时按它找回同一条
 * @param ingredients 每一样料能用哪几种物品（物品 ID，任一种都行）；一份配方每样要一件
 * @param trigger     哪一样是触发的：它泡在流体里够久、身边凑齐别的料才变；扔的时候它先下水，只留份数件
 * @param resultId    产物的物品 ID
 * @param resultCount 一份出几件
 * @param fluids      认哪几种流体（流体 ID，例如 minecraft:water、minecraft:flowing_water）
 */
public record FluidTransform(String id, List<List<String>> ingredients, int trigger, String resultId, int resultCount,
                             List<String> fluids) {

    public FluidTransform {
        Objects.requireNonNull(id, "id");
        ingredients = ingredients.stream().map(List::copyOf).toList();
        Objects.requireNonNull(resultId, "resultId");
        fluids = List.copyOf(fluids);
        if (ingredients.isEmpty() || ingredients.stream().anyMatch(List::isEmpty)) {
            throw new IllegalArgumentException("配方的每一样料至少要有一种物品：" + id);
        }
        if (trigger < 0 || trigger >= ingredients.size()) {
            throw new IllegalArgumentException("触发的那一样不在料里：" + id);
        }
        if (resultCount < 1) throw new IllegalArgumentException("一份至少出一件：" + id);
    }
}
