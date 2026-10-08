// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

/**
 * 物品与栏位匹配的只读视图：一件东西按游戏自己的规则能不能放进某个装备栏。
 *
 * <p>"物品类型决定能放进哪个装备栏"是游戏事实，只在游戏接口层读一次；
 * 提交穿装备前按它核对，头盔进不了胸甲栏。
 */
public interface ReadsGearFit {

    /** 这件物品按游戏规则能不能放进这个栏位。 */
    boolean fits(String itemId, GearSlotName slot);
}
