// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.List;
import java.util.Optional;

/**
 * 界面内容的读取接缝：打开的容器界面两侧各有什么，读的时候按布局分好侧。
 *
 * <p>实现接在游戏接口层的菜单入口上：界面刚打开、格子还没同步完时给空，
 * 不把没同步的界面当成"空的容器"报出去。测试用替身摆内容。
 */
public interface MenuContent {

    /**
     * 当前打开界面的分侧读数；没有打开的界面、或内容还没同步完时给空。
     * 槽位号与快照一一对应，搬运确认按这些槽位动手前先取快照。
     */
    Optional<Reading> current();

    /**
     * 一次分侧读数。
     *
     * @param channel            界面通道，认领与关闭都经它
     * @param slots              布局判定的输入
     * @param containerSlotIds   容器那一侧的槽位号
     * @param playerSlotIds      角色背包那一侧的槽位号
     * @param containerSnapshots 与 containerSlotIds 对齐的快照
     * @param playerSnapshots    与 playerSlotIds 对齐的快照
     */
    record Reading(MenuChannel channel, MenuSlots slots,
                   List<Integer> containerSlotIds, List<Integer> playerSlotIds,
                   List<SlotSnapshot> containerSnapshots, List<SlotSnapshot> playerSnapshots) {
        public Reading {
            if (channel == null || slots == null) throw new IllegalArgumentException("界面读数缺通道或槽位描述");
            if (containerSlotIds.size() != containerSnapshots.size()
                    || playerSlotIds.size() != playerSnapshots.size()) {
                throw new IllegalArgumentException("槽位号与快照数量不一致");
            }
            containerSlotIds = List.copyOf(containerSlotIds);
            playerSlotIds = List.copyOf(playerSlotIds);
            containerSnapshots = List.copyOf(containerSnapshots);
            playerSnapshots = List.copyOf(playerSnapshots);
        }
    }
}
