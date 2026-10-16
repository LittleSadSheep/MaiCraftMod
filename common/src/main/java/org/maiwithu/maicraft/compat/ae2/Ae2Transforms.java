// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;
import java.util.Objects;

import net.minecraft.world.level.Level;

/**
 * 读 AE2 转化配方的模组读写接缝：客户端同步过来的配方表里，"泡在流体里"的那几条各要什么、出什么、认哪种流体。
 * 爆炸那几条不在这里。只用 Minecraft 与 Java 的类型；读写端在 NeoForge 模块里直接读 AE2 的配方类型。
 */
public interface Ae2Transforms {

    /** 读不到配方：没有读写端时用，一条都不给。 */
    Ae2Transforms NONE = level -> List.of();

    /** 这个世界的配方表里泡在流体里的转化配方；读不到给空表。 */
    List<Recipe> fluidRecipes(Level level);

    /**
     * 一条泡在流体里的转化配方。料按配方里写的先后排，第一样是触发的那一样（它泡在水里够久、身边凑齐别的料才变）。
     *
     * @param id          配方 ID
     * @param ingredients 每一样料能用哪几种物品（物品 ID）
     * @param resultId    产物的物品 ID
     * @param resultCount 一份出几件
     * @param fluids      认哪几种流体（流体 ID）
     */
    record Recipe(String id, List<List<String>> ingredients, String resultId, int resultCount, List<String> fluids) {

        public Recipe {
            Objects.requireNonNull(id, "id");
            ingredients = ingredients.stream().map(List::copyOf).toList();
            Objects.requireNonNull(resultId, "resultId");
            fluids = List.copyOf(fluids);
        }
    }
}
