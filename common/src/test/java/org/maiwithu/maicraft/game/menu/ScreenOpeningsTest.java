// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 界面打开记录判谁开的：自动化拿着角色且刚出过手才算任务开的，其余算人开的。 */
class ScreenOpeningsTest {

    @Test
    void 自动化刚右键过就出现的界面是任务开的() {
        // 第 100 刻右键箱子，服务端回包后第 104 刻看到箱子界面：这一下开出来的。
        assertEquals(ScreenOpening.OpenedBy.TASK, ScreenOpenings.openedBy(true, 104, 100));
        // 窗口边上还算：网络抖动时开界面的包可能晚到两秒。
        assertEquals(ScreenOpening.OpenedBy.TASK,
                ScreenOpenings.openedBy(true, 100 + ScreenOpenings.TASK_WINDOW_TICKS, 100));
    }

    @Test
    void 自动化好一阵没出手时出现的界面不算任务开的() {
        // 自动化拿着角色在走路，人按 E 开了背包，或服务器自己弹了界面：不是任务点的。
        assertEquals(ScreenOpening.OpenedBy.PLAYER,
                ScreenOpenings.openedBy(true, 101 + ScreenOpenings.TASK_WINDOW_TICKS, 100));
        // 从没出过手（刻号记成最小值）也不能因为相减溢出被算成任务开的。
        assertEquals(ScreenOpening.OpenedBy.PLAYER, ScreenOpenings.openedBy(true, 5, Long.MIN_VALUE));
    }

    @Test
    void 人拿着角色时出现的界面是人开的() {
        // 人按 F8 收回角色后自己右键箱子：哪怕自动化刚出过手，也是人开的。
        assertEquals(ScreenOpening.OpenedBy.PLAYER, ScreenOpenings.openedBy(false, 104, 100));
    }
}
