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

/** Samples MaiCraft's leased camera curve once per rendered frame instead of once per game tick. */
@Mixin(GameRenderer.class)
public abstract class GameRendererCameraMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void maicraft$advanceCamera(
            DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callback) {
        ClientRuntime.renderFrame(Minecraft.getInstance());
    }

    /** Suppress only the automatic focus-loss pause; ESC still uses the native pause path. */
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;pauseGame(Z)V"))
    private void maicraft$pauseWhenHumanControlled(Minecraft minecraft, boolean pauseOnly) {
        if (!ClientRuntime.actor().automationControlRequested()) minecraft.pauseGame(pauseOnly);
    }
}
