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
}
