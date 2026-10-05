// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.debug;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.maiwithu.maicraft.client.preview.PreviewConfig;

/**
 * 把每刻快照画成两段式面板：上方固定状态行（灰标签彩值），下方聊天框式事件区
 * （着色片段按顺序拼排）。只读绘制，所有内容来自 DebugHudController 在游戏刻构建的快照。
 */
public final class DebugHudRenderer {
    private static final int BACKGROUND = 0x9A101018;
    private static final int LABEL = 0xFFA8A8A8;
    private static final int LINE_HEIGHT = 10;
    private static final int SECTION_GAP = 4;
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
        DebugHudController.Snapshot snapshot = DebugHudController.snapshot();
        if (snapshot.rows().isEmpty()) return;
        Font font = minecraft.font;

        // 背景框宽度以快照携带的面板宽度为准——它与事件区、最新报错的折行是同一个口径，
        // 折行右缘因此贴住框的内容右缘；正文行实测宽度只作兜底，正常不超出该口径。
        int contentWidth = 0;
        for (DebugHudController.Row row : snapshot.rows()) {
            contentWidth = Math.max(contentWidth, font.width(row.label().isBlank() ? "" : row.label() + ": ")
                    + font.width(row.value()));
        }
        for (DebugHudController.EventLine line : snapshot.events()) {
            int segments = 0;
            for (DebugHudController.Segment segment : line.segments()) {
                segments += font.width(segment.text());
            }
            contentWidth = Math.max(contentWidth, segments);
        }
        int width = Math.max(snapshot.panelWidth(), contentWidth);

        // 事件区钉在面板底部：固定行永远从面板顶端开始，不随事件多少上下跳动。
        int eventHeight = snapshot.events().isEmpty() ? 0
                : SECTION_GAP + snapshot.events().size() * LINE_HEIGHT;
        graphics.fill(2, 2, 8 + width,
                6 + snapshot.rows().size() * LINE_HEIGHT + eventHeight, BACKGROUND);

        int y = 6;
        for (DebugHudController.Row row : snapshot.rows()) {
            int valueX = 4;
            if (!row.label().isBlank()) {
                String label = row.label() + ": ";
                graphics.drawString(font, label, 4, y, LABEL);
                valueX = 4 + font.width(label);
            }
            drawSegment(font, graphics, row.value(), valueX, y, row.color());
            y += LINE_HEIGHT;
        }

        y += SECTION_GAP;
        for (DebugHudController.EventLine line : snapshot.events()) {
            int x = 4;
            for (DebugHudController.Segment segment : line.segments()) {
                drawSegment(font, graphics, segment.text(), x, y, segment.color());
                x += font.width(segment.text());
            }
            y += LINE_HEIGHT;
        }
    }

    private static void drawSegment(Font font, GuiGraphics graphics, String text,
                                    int x, int y, ChatFormatting color) {
        graphics.drawString(font, text, x, y,
                color.getColor() == null ? 0xFFFFFFFF : 0xFF000000 | color.getColor());
    }
}
