// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;

import org.maiwithu.maicraft.game.mixin.GuiOverlayMessageAccessor;

/**
 * 动作栏提示语的读取端：读原版动作栏此刻显示的文案，供"游戏的拒绝"判断使用。
 *
 * <p>动作栏文案是游戏事实：服务器与模组把"这样用不行"之类的话写在这里，读不到（没有在显示）
 * 时如实返回 null，不把上一次的旧话当新话。文案以玩家看到的文字为准（取已翻译的字符串）。
 */
public final class OverlayMessages {

    /** 读动作栏此刻的提示语文字；没有在显示时为 null。 */
    public String latest() {
        Gui gui = Minecraft.getInstance().gui;
        if (gui == null) return null;
        Component message = ((GuiOverlayMessageAccessor) gui).maicraft$overlayMessage();
        if (message == null) return null;
        String text = message.getString();
        return text.isBlank() ? null : text;
    }
}
