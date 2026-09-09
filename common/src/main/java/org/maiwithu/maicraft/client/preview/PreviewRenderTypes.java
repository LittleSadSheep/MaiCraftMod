// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderStateShard;
import java.util.List;

/** Litematica-style terrain layers: solid/cutout write depth; translucent blocks and overlays test it. */
final class PreviewRenderTypes extends RenderType {
    static final RenderType SOLID = new PreviewRenderTypes("maicraft_preview_solid", DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS, false, List.of(RENDERTYPE_SOLID_SHADER, BLOCK_SHEET_MIPPED,
                    NO_TRANSPARENCY, CULL, LIGHTMAP, COLOR_DEPTH_WRITE, LEQUAL_DEPTH_TEST, MAIN_TARGET));
    static final RenderType CUTOUT = new PreviewRenderTypes("maicraft_preview_cutout", DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS, false, List.of(RENDERTYPE_CUTOUT_SHADER, BLOCK_SHEET_MIPPED,
                    NO_TRANSPARENCY, CULL, LIGHTMAP, COLOR_DEPTH_WRITE, LEQUAL_DEPTH_TEST, MAIN_TARGET));
    static final RenderType TRANSLUCENT = new PreviewRenderTypes("maicraft_preview_translucent", DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS, true, List.of(RENDERTYPE_TRANSLUCENT_SHADER, BLOCK_SHEET_MIPPED,
                    TRANSLUCENT_TRANSPARENCY, CULL, LIGHTMAP, COLOR_WRITE, LEQUAL_DEPTH_TEST, MAIN_TARGET));
    static final RenderType OUTLINE = new PreviewRenderTypes("maicraft_preview_outline",
            DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, false,
            List.of(RENDERTYPE_LINES_SHADER, TRANSLUCENT_TRANSPARENCY, NO_CULL, COLOR_WRITE,
                    VIEW_OFFSET_Z_LAYERING, LEQUAL_DEPTH_TEST, MAIN_TARGET));
    static final RenderType PARTS = new PreviewRenderTypes("maicraft_preview_parts", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, false, List.of(POSITION_COLOR_SHADER, NO_TRANSPARENCY,
                    CULL, COLOR_DEPTH_WRITE, LEQUAL_DEPTH_TEST, MAIN_TARGET));

    private PreviewRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode,
                               boolean sort, List<RenderStateShard> states) {
        // 1.21.1's CompositeRenderType factory is package-private. The public RenderType
        // constructor supports the same setup/clear state contract without reflection or ATs.
        super(name, format, mode, 262144, false, sort,
                () -> states.forEach(RenderStateShard::setupRenderState),
                () -> states.forEach(RenderStateShard::clearRenderState));
    }
}
