// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.WinScreen;

/**
 * 终末之诗：没看过制作人员名单的玩家第一次从末地走进出口门，原版先放终末之诗和名单（十多分钟），
 * 这张界面关掉才发重生请求，把人送回重生点。关它和玩家按 Esc 是同一个入口。
 */
public final class EndPoemScreen {

    private EndPoemScreen() {}

    /** 屏幕上是不是终末之诗。 */
    public static boolean showing() {
        return Minecraft.getInstance().screen instanceof WinScreen;
    }

    /** 关掉终末之诗（等同按 Esc）：原版随即发重生请求。不在屏幕上就什么都不做；送没送回去看之后的维度。 */
    public static void close() {
        if (Minecraft.getInstance().screen instanceof WinScreen poem) {
            poem.onClose();
        }
    }
}
