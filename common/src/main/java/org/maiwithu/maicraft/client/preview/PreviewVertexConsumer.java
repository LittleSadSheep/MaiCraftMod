// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Preserve model UVs and shading, applying ghost alpha and full-bright inspection lighting. */
final class PreviewVertexConsumer implements VertexConsumer {
    private final VertexConsumer delegate;
    PreviewVertexConsumer(VertexConsumer delegate) { this.delegate = delegate; }
    @Override public VertexConsumer addVertex(float x, float y, float z) { delegate.addVertex(x, y, z); return this; }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) {
        delegate.setColor(r, g, b, Math.round(a * .38f)); return this;
    }
    @Override public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
    @Override public VertexConsumer setUv1(int u, int v) { delegate.setUv1(u, v); return this; }
    @Override public VertexConsumer setUv2(int u, int v) { delegate.setUv2(240, 240); return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { delegate.setNormal(x, y, z); return this; }
}
