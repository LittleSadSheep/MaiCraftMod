// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 每次画面开始绘制时更新自动控制的镜头，让转头跟随帧率平滑变化；控制权仍由 ClientRuntime 判断。 */
@Mixin(GameRenderer.class)
public abstract class GameRendererCameraMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void maicraft$advanceCamera(
            DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callback) {
        ClientRuntime.renderFrame(Minecraft.getInstance());
    }

    /** 自动控制生效时跳过渲染流程中的失焦暂停；玩家主动按 ESC 的原版暂停入口不受这段替换影响。 */
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;pauseGame(Z)V"))
    private void maicraft$pauseWhenHumanControlled(Minecraft minecraft, boolean pauseOnly) {
        if (!ClientRuntime.actor().effectiveAutomationControlRequested()) minecraft.pauseGame(pauseOnly);
    }
}
