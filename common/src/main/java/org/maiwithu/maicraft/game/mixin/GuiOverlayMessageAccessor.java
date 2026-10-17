// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读原版动作栏当前显示的提示语：动作栏文案是游戏事实，读取侧在这里开一个口。 */
@Mixin(Gui.class)
public interface GuiOverlayMessageAccessor {

    /** 动作栏此刻的提示语；没有在显示时为 null。 */
    @Accessor("overlayMessageString")
    Component maicraft$overlayMessage();

    /**
     * 动作栏提示还要显示几刻：每冒出一条新提示原版就把它重置成满格（60 刻），之后每刻减一，减到 0 就不显示了。
     * 同一句话又冒出来一次时文字不变，只有它会跳回满格，靠它认得出"又说了一遍"。
     */
    @Accessor("overlayMessageTime")
    int maicraft$overlayMessageTime();
}
