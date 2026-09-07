// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.maiwithu.maicraft.client.debug.NavigationPathRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shared Mojmap render boundary for NeoForge and Fabric. */
@Mixin(LevelRenderer.class)
public abstract class NavigationPathRenderMixin {
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void maicraft$renderNavigationPath(DeltaTracker deltaTracker, boolean renderBlockOutline,
            Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
            Matrix4f view, Matrix4f projection, CallbackInfo callback) {
        NavigationPathRenderer.render(camera, view, projection);
    }
}
