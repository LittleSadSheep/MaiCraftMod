// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import java.util.Optional;

/**
 * 装备栏只读视图：主手、副手和四格护甲上现在拿着什么。
 *
 * <p>装备栏不占背包主格，背包视图里看不到它；穿、卸、吃之前先看它，
 * "同一个栏位内容"这一条游戏事实全仓只在这里读一次。每格给与背包同一张快照
 * （{@link BackpackStack}），副手可能有多件、护甲每格一件。
 */
public interface ReadsEquipment {

    /** 某个装备栏上的那一格；空着时为 empty。 */
    Optional<BackpackStack> slot(GearSlotName name);
}
