// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.debug;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.maiwithu.maicraft.client.preview.PreviewConfig;

/**
 * 把每刻快照画成 F3 风格的左上面板：半透明底、灰标签彩值。只读绘制，不在这里查询任务状态，
 * 所有内容来自 DebugHudController 在游戏刻构建的快照。
 */
public final class DebugHudRenderer {
    private static final int BACKGROUND = 0x9A101018;
    private static final int LABEL = 0xFFA8A8A8;
    private static final int LINE_HEIGHT = 10;
    private DebugHudRenderer() {}

    /** 常规 HUD 层入口；界面打开时原版不渲染这一层，由屏幕层入口补画。 */
    public static void render(GuiGraphics graphics) {
        draw(graphics);
    }

    /** 屏幕层入口：菜单流调试时面板仍然可见，与常规 HUD 层同一份快照。 */
    public static void renderScreen(GuiGraphics graphics) {
        draw(graphics);
    }

    private static void draw(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui
                || !PreviewConfig.hudVisible(minecraft.gameDirectory.toPath())) return;
        List<DebugHudController.Row> rows = DebugHudController.snapshot();
        if (rows.isEmpty()) return;
        Font font = minecraft.font;
        int width = 0;
        for (DebugHudController.Row row : rows) {
            width = Math.max(width, font.width(row.label().isBlank() ? "" : row.label() + ": ")
                    + font.width(row.value()));
        }
        graphics.fill(2, 2, 8 + width, 6 + rows.size() * LINE_HEIGHT, BACKGROUND);
        int y = 6;
        for (DebugHudController.Row row : rows) {
            // 空标签是事件尾窗的续行，不画前缀，让多条事件读起来像一段滚动的时间线。
            int valueX = 4;
            if (!row.label().isBlank()) {
                String label = row.label() + ": ";
                graphics.drawString(font, label, 4, y, LABEL);
                valueX = 4 + font.width(label);
            }
            ChatFormatting color = row.color();
            graphics.drawString(font, row.value(), valueX, y,
                    color.getColor() == null ? 0xFFFFFFFF : 0xFF000000 | color.getColor());
            y += LINE_HEIGHT;
        }
    }
}
