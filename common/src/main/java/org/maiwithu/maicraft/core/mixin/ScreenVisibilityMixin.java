// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 界面这一帧确实画完后通知菜单观察器，防止只创建了菜单对象、玩家还没看到它时就开始点击。 */
@Mixin(Screen.class)
public abstract class ScreenVisibilityMixin {
    @Inject(method = "renderWithTooltip", at = @At("RETURN"))
    private void maicraft$observeMenuFrame(GuiGraphics graphics, int mouseX, int mouseY,
                                          float partialTick, CallbackInfo callback) {
        MenuVisibility.rendered((Screen) (Object) this);
    }
}
