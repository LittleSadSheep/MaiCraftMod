// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderStateShard;
import java.util.List;

/**
 * 为预览各类图形配置透明混合、纹理和深度比较。仍会被真实地形挡住，但只写颜色，不把预览写进世界的深度。
 */
final class PreviewRenderTypes extends RenderType {
    static final RenderType SOLID = new PreviewRenderTypes("maicraft_preview_solid", DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS, true, List.of(RENDERTYPE_SOLID_SHADER, BLOCK_SHEET_MIPPED,
                    TRANSLUCENT_TRANSPARENCY, CULL, LIGHTMAP, COLOR_WRITE, LEQUAL_DEPTH_TEST, MAIN_TARGET));
    static final RenderType CUTOUT = new PreviewRenderTypes("maicraft_preview_cutout", DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS, true, List.of(RENDERTYPE_CUTOUT_SHADER, BLOCK_SHEET_MIPPED,
                    TRANSLUCENT_TRANSPARENCY, CULL, LIGHTMAP, COLOR_WRITE, LEQUAL_DEPTH_TEST, MAIN_TARGET));
    static final RenderType TRANSLUCENT = new PreviewRenderTypes("maicraft_preview_translucent", DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS, true, List.of(RENDERTYPE_TRANSLUCENT_SHADER, BLOCK_SHEET_MIPPED,
                    TRANSLUCENT_TRANSPARENCY, CULL, LIGHTMAP, COLOR_WRITE, LEQUAL_DEPTH_TEST, MAIN_TARGET));
    static final RenderType OUTLINE = new PreviewRenderTypes("maicraft_preview_outline",
            DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, false,
            List.of(RENDERTYPE_LINES_SHADER, TRANSLUCENT_TRANSPARENCY, NO_CULL, COLOR_WRITE,
                    VIEW_OFFSET_Z_LAYERING, LEQUAL_DEPTH_TEST, MAIN_TARGET));
    static final RenderType PARTS = new PreviewRenderTypes("maicraft_preview_parts", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, false, List.of(POSITION_COLOR_SHADER, TRANSLUCENT_TRANSPARENCY,
                    CULL, COLOR_WRITE, LEQUAL_DEPTH_TEST, MAIN_TARGET));

    private PreviewRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode,
                               boolean sort, List<RenderStateShard> states) {
        // 通过公开构造器依次设置和清理这些渲染状态，避免访问原版包内可见的组合工厂。
        super(name, format, mode, 262144, false, sort,
                () -> states.forEach(RenderStateShard::setupRenderState),
                () -> states.forEach(RenderStateShard::clearRenderState));
    }
}
