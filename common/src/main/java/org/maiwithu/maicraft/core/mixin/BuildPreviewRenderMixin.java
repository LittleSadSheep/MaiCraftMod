// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;
import org.maiwithu.maicraft.client.preview.PreviewRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Uses the same Mojmap world-render boundary on both supported loaders. */
@Mixin(LevelRenderer.class)
public abstract class BuildPreviewRenderMixin {
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void maicraft$renderBuildPreview(DeltaTracker deltaTracker, boolean renderBlockOutline,
            Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
            Matrix4f view, Matrix4f projection, CallbackInfo callback) {
        PreviewRenderer.render(camera, view, projection);
    }

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void maicraft$refreshBuildPreview(CallbackInfo callback) { PreviewRenderer.invalidate(); }

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void maicraft$reloadBuildPreview(ResourceManager resources, CallbackInfo callback) {
        PreviewRenderer.invalidate();
    }
}
