// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import net.minecraft.client.MouseHandler;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 自动控制要求鼠标保持释放时，取消原版抓回鼠标的动作，避免关箱子或点击游戏后突然锁住玩家光标。 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerControlMixin {
    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void maicraft$keepCursorReleased(CallbackInfo callback) {
        if (ClientRuntime.actor().preventsMouseGrab()) callback.cancel();
    }
}
