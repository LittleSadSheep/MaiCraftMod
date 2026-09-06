// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import net.minecraft.client.MouseHandler;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Closing a container or clicking the game must not recapture the cursor during automation. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerControlMixin {
    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void maicraft$keepCursorReleased(CallbackInfo callback) {
        if (ClientRuntime.actor().preventsMouseGrab()) callback.cancel();
    }
}
