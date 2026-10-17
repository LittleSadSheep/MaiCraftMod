// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.Optional;

/**
 * 看角色此刻开着的界面的只读接缝：观察界面与结束事件的一行自身摘要都读它。
 * 实现接在游戏接口层的菜单入口与界面打开记录上；测试用替身摆界面。
 */
public interface ReadsOpenScreen {

    /** 此刻开着的界面；什么都没开（只有画面上的世界）时为空。 */
    Optional<OpenScreen> current();
}
