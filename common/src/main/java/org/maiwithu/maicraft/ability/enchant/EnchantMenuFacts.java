// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.enchant;

import java.util.List;

/**
 * 附魔相关的只读事实：身上每格的附魔事实、开着的附魔台菜单此刻的样子。
 * 生产读端从角色上下文读，测试换成画好的替身。
 */
interface EnchantMenuFacts {

    /** 身上（主背包三十六格加副手）逐格的附魔事实。 */
    List<ItemFacts> carriedSlots();

    /** 此刻开着的界面是不是附魔台菜单（编号与对象都对得上的那一份）。 */
    boolean menuOpen();

    /**
     * 附魔台菜单此刻的一次读数：三档的门槛、线索与线索等级、随机种子、两格工作槽、
     * 光标、玩家等级与创造模式。界面没开或不是附魔台的给 null。
     */
    Snapshot readMenu();

    /**
     * 附魔台菜单的一次读数。clue 是线索附魔的注册 ID（读端从原版同步的序号换算）；
     * 随机种子与三档读数一起构成报价指纹，提交前照它核对报价变没变。
     */
    record Snapshot(int[] costs, String[] clues, int[] clueLevels, int seed,
                    ItemFacts itemSlot, ItemFacts lapisSlot, boolean cursorEmpty,
                    int playerLevel, boolean creative) {
    }
}
