// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe the actual rendered screen in both vanilla and NeoForge's layered GUI dispatcher. */
@Mixin(Screen.class)
public abstract class ScreenVisibilityMixin {
    @Inject(method = "renderWithTooltip", at = @At("RETURN"))
    private void maicraft$observeMenuFrame(GuiGraphics graphics, int mouseX, int mouseY,
                                          float partialTick, CallbackInfo callback) {
        MenuVisibility.rendered((Screen) (Object) this);
    }
}
