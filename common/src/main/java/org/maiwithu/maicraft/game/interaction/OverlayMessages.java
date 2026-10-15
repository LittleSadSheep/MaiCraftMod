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

    /**
     * 动作栏上正显示着的一条提示：文字，和它还要显示几刻。
     *
     * @param text      玩家看到的文字
     * @param ticksLeft 还要显示几刻；原版每冒出一条新提示都重置成满格，之后每刻减一
     */
    public record Shown(String text, int ticksLeft) {

        /**
         * 这条是不是比上次读到的那条更新：上次没在显示、文字变了，或者剩余显示刻数跳回去变多了
         * （同一句话又说了一遍，文字一样，只有剩余刻数会跳回满格）。剩余刻数只会一刻一刻往下减，
         * 变多只能是又冒出了一条。
         */
        public boolean newerThan(Shown earlier) {
            return earlier == null || !text.equals(earlier.text) || ticksLeft > earlier.ticksLeft;
        }
    }

    /** 读动作栏此刻正显示着的提示，连同还要显示几刻；没有在显示时为 null。 */
    public Shown shown() {
        Gui gui = Minecraft.getInstance().gui;
        if (gui == null) return null;
        GuiOverlayMessageAccessor overlay = (GuiOverlayMessageAccessor) gui;
        Component message = overlay.maicraft$overlayMessage();
        int ticksLeft = overlay.maicraft$overlayMessageTime();
        if (message == null || ticksLeft <= 0) return null;
        String text = message.getString();
        return text.isBlank() ? null : new Shown(text, ticksLeft);
    }

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
