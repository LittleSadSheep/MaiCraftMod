// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.util.List;
import java.util.OptionalDouble;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * 世界叠加共用的两种画法：透过地形的线（导航路线、包围盒），和按深度遮挡的半透明面（预览里的目标方块）。
 * 两种都只写颜色不写深度，画完把状态恢复原样，不影响原版接下来画的东西。
 */
final class OverlayRenderTypes {

    private OverlayRenderTypes() {}

    /** 线：暂时关掉深度比较，隔着墙也看得见。 */
    static final RenderType LINES = Overlay.LINES;

    /** 半透明面：按深度遮挡，正反面都画。 */
    static final RenderType FILLED = Overlay.FILLED;

    // RenderStateShard 的各段状态是受保护的常量，只有 RenderType 的子类拿得到，所以画法在这个内部类里定义。
    private static final class Overlay extends RenderType {
        static final RenderType LINES = new Overlay("maicraft_overlay_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
                VertexFormat.Mode.LINES, List.of(RENDERTYPE_LINES_SHADER, new RenderStateShard.LineStateShard(OptionalDouble.of(2.5)),
                        TRANSLUCENT_TRANSPARENCY, NO_CULL, COLOR_WRITE,
                        new RenderStateShard("maicraft_overlay_no_depth", RenderSystem::disableDepthTest, RenderSystem::enableDepthTest) {},
                        MAIN_TARGET));
        static final RenderType FILLED = new Overlay("maicraft_overlay_filled", DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS, List.of(POSITION_COLOR_SHADER, TRANSLUCENT_TRANSPARENCY, NO_CULL, COLOR_WRITE,
                        LEQUAL_DEPTH_TEST, MAIN_TARGET));

        private Overlay(String name, VertexFormat format, VertexFormat.Mode mode, List<RenderStateShard> states) {
            super(name, format, mode, 32768, false, false,
                    () -> states.forEach(RenderStateShard::setupRenderState),
                    () -> states.forEach(RenderStateShard::clearRenderState));
        }
    }
}
