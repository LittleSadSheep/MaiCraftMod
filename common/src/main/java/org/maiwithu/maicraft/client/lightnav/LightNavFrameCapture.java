// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.lightnav;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Reads only Minecraft's framebuffer; image encoding runs outside the render thread. */
public final class LightNavFrameCapture {
    private LightNavFrameCapture() {}

    /** The PNG preserves the source aspect ratio and has no desktop/window chrome. */
    public record Frame(byte[] png, int width, int height, int sourceWidth, int sourceHeight) {}

    /**
     * Call at the end of a rendered frame, after applying the caller's interval/in-flight gate.
     * The framebuffer read must run on the render thread; the supplied executor owns all resizing
     * and PNG compression. Native image memory is released even if the executor rejects the work.
     */
    public static CompletableFuture<Frame> capturePng(
            Minecraft minecraft, int maxDimension, Executor encoder) {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(encoder, "encoder");
        if (maxDimension < 64 || maxDimension > 1280) {
            throw new IllegalArgumentException("maxDimension must be between 64 and 1280");
        }
        RenderSystem.assertOnRenderThread();
        if (minecraft.level == null || minecraft.player == null || minecraft.noRender
                || minecraft.getMainRenderTarget().width <= 0
                || minecraft.getMainRenderTarget().height <= 0) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Minecraft has no rendered player view"));
        }

        // Vanilla's screenshot reader also flips OpenGL's bottom-up rows to upright image rows.
        NativeImage captured = Screenshot.takeScreenshot(minecraft.getMainRenderTarget());
        CompletableFuture<Frame> result = new CompletableFuture<>();
        try {
            encoder.execute(() -> {
                try (captured) {
                    if (!result.isCancelled()) result.complete(encode(captured, maxDimension));
                } catch (Exception failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            captured.close();
            result.completeExceptionally(failure);
        }
        return result;
    }

    private static Frame encode(NativeImage source, int maxDimension) throws IOException {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        double scale = Math.min(1.0, (double) maxDimension / Math.max(sourceWidth, sourceHeight));
        int width = Math.max(1, (int) Math.round(sourceWidth * scale));
        int height = Math.max(1, (int) Math.round(sourceHeight * scale));
        if (width == sourceWidth && height == sourceHeight) {
            return new Frame(source.asByteArray(), width, height, sourceWidth, sourceHeight);
        }
        try (NativeImage resized = new NativeImage(width, height, false)) {
            source.resizeSubRectTo(0, 0, sourceWidth, sourceHeight, resized);
            return new Frame(resized.asByteArray(), width, height, sourceWidth, sourceHeight);
        }
    }
}
