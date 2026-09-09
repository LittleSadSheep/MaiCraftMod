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

/** 在原版世界画完后追加蓝图预览，两个加载器共用；这里只画客户端效果。 */
@Mixin(LevelRenderer.class)
public abstract class BuildPreviewRenderMixin {
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void maicraft$renderBuildPreview(DeltaTracker deltaTracker, boolean renderBlockOutline,
            Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
            Matrix4f view, Matrix4f projection, CallbackInfo callback) {
        PreviewRenderer.render(camera, view, projection);
    }

    // 原版要求重建场景时同步清掉预览缓存，避免留着旧几何。
    @Inject(method = "allChanged", at = @At("HEAD"))
    private void maicraft$refreshBuildPreview(CallbackInfo callback) { PreviewRenderer.invalidate(); }

    // 资源包重载会在同一个模型管理对象里换缓存，因此必须在这个回调主动重建预览。
    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void maicraft$reloadBuildPreview(ResourceManager resources, CallbackInfo callback) {
        PreviewRenderer.invalidate();
    }
}
