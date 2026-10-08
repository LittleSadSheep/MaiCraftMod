// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.InBedChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;

import org.junit.jupiter.api.Test;

/** 界面与输入允许的判定：聊天框不打断走路，其他对话框必须先退出，背包界面永远不算世界画面。 */
class MenuVisibilityTest {

    @Test
    void worldInputAllowsChatButNoOtherDialog() {
        assertTrue(MenuVisibility.worldInputAllowed(null), "世界画面允许行走");
        assertTrue(MenuVisibility.worldInputAllowed(new ChatScreen("")), "聊天框不打断走路");
        assertFalse(MenuVisibility.worldInputAllowed(new PauseScreen(false)), "暂停等其他对话框必须先退出");
    }

    @Test
    void theInBedChatScreenKeepsThePlayerStill() {
        // 床上的聊天界面虽然长得像聊天框，但必须保持静止等自然醒，不能让旧导航继续移动。
        assertFalse(MenuVisibility.worldInputAllowed(new InBedChatScreen()));
    }
}
