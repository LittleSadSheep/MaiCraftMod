// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.recipe;

import java.util.Objects;

/**
 * 配方里画出来的一样东西：一堆物品、一份流体，或查看器认得、但既不是物品也不是流体的东西（例如通用机械的化学品）。
 *
 * @param kind   是物品、流体还是别的
 * @param id     注册 ID，例如 minecraft:iron_ingot、minecraft:water
 * @param name   当前游戏语言里的名字；读不到时用 ID
 * @param amount 物品是件数，流体是毫桶，别的东西照查看器给的数
 * @param chance 出这一份的几率，0 到 1；1 表示查看器没标几率（必出或没写）。查看器标的是展示用的数，
 *               配方真实的几率以配方的原始定义为准
 */
public record ShownStack(Kind kind, String id, String name, long amount, double chance) {

    public ShownStack {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        name = name == null || name.isBlank() ? id : name;
        if (amount < 0) throw new IllegalArgumentException("数量不能是负数：" + amount);
        if (!(chance > 0 && chance <= 1)) throw new IllegalArgumentException("几率要在 0 到 1 之间：" + chance);
    }

    /** 一件物品堆，没标几率。 */
    public static ShownStack item(String id, String name, long count) {
        return new ShownStack(Kind.ITEM, id, name, count, 1);
    }

    /** 这一份标了几率没有：只有小于 1 才算标了。 */
    public boolean hasChance() {
        return chance < 1;
    }

    /** 配方里一样东西是什么。 */
    public enum Kind {
        /** 物品，数量是件数。 */
        ITEM,
        /** 流体，数量是毫桶（一桶 1000）。 */
        FLUID,
        /** 查看器认得、但不是物品也不是流体的东西，数量照查看器给的数。 */
        OTHER
    }
}
