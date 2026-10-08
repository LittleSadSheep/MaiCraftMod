// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

/**
 * 物品价值分级：腾地方时把物品从贵到贱排，越靠前越不能动。
 *
 * <p>丢东西从垃圾级开始往上丢；一旦要丢到稀有贵重这一级，就得先停下来问。
 */
enum Worth {
    /** 这个任务正要用的东西，动都不动。 */
    TASK_RESERVED,
    /** 工具、武器、护甲：干活和保命靠它。 */
    GEAR,
    /** 稀有贵重：带附魔、标了稀有度，丢了很难补回来。 */
    PRECIOUS,
    /** 能吃的：饿了要靠它。 */
    FOOD,
    /** 整块建材：能搭能垫，也最容易再弄到。 */
    BUILDING_MATERIAL,
    /** 普通掉落：没什么用也没什么损失。 */
    COMMON_LOOT,
    /** 垃圾：堆多了一文不值的方块和腐肉。 */
    JUNK;

    /** 要丢到这一级或更贵，就不能自作主张，先停下来问。 */
    static final Worth ASK_BEFORE = PRECIOUS;

    /** 这一格是不是已经到了"不能自动丢"的等级（贵重及以上）。 */
    boolean tooValuableToDrop() {
        return ordinal() <= ASK_BEFORE.ordinal();
    }
}
