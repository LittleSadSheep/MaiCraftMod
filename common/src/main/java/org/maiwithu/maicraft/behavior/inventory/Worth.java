// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

/**
 * 物品价值分级：腾地方时把物品从贵到贱排，越靠前越不能动。
 *
 * <p>丢东西从垃圾级开始往上丢：垃圾 → 顺手捡的零碎 → 建材 → 食物；"有用的"及以上不自作主张丢，
 * 能存进容器、网络就存，存不了先停下来问。原先做出来的零件、链条、桶都落在"普通掉落"先丢，
 * 蜘蛛眼、蘑菇、生肉、树苗反倒留着。
 */
enum Worth {
    /** 这个任务正要用的东西，动都不动。 */
    TASK_RESERVED,
    /** 工具、武器、护甲：干活和保命靠它。 */
    GEAR,
    /** 稀有贵重：带附魔、标了稀有度，丢了很难补回来。 */
    PRECIOUS,
    /** 有用的：做出来的零件、锭、机器方块，说不准哪天要用的材料。能存就存，不自作主张丢。 */
    USEFUL,
    /** 能吃的：饿了要靠它。 */
    FOOD,
    /** 整块建材：能搭能垫，也最容易再弄到。 */
    BUILDING_MATERIAL,
    /** 顺手捡的零碎：树苗、蘑菇、花、种子、生肉、蜘蛛眼、骨头这类意料之外又没什么用的战利品。 */
    INCIDENTAL,
    /** 垃圾：堆多了一文不值的方块和腐肉。 */
    JUNK;

    /** 要丢到这一级或更贵，就不能自作主张，先停下来问（存进容器、网络不用问）。 */
    static final Worth ASK_BEFORE = USEFUL;

    /** 这一格是不是已经到了"不能自动丢"的等级（有用的及以上）。 */
    boolean tooValuableToDrop() {
        return ordinal() <= ASK_BEFORE.ordinal();
    }
}
