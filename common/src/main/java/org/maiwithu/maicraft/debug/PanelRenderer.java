// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 把排好的行画在左上角：半透明深色底，标签在左、正文从标签栏右边起，段与段之间一条细线。
 * 只照着画，不做任何判断；宽度在排版时已经定好，背景框和折行用的是同一个宽度。
 */
final class PanelRenderer {
    private static final int BACKGROUND = 0x9A101018;
    private static final int DIVIDER = 0x40FFFFFF;
    private static final int LINE_HEIGHT = 10;
    private static final int DIVIDER_HEIGHT = 5;
    private static final int LEFT = 2;
    private static final int TOP = 2;
    private static final int PADDING = 3;

    private PanelRenderer() {}

    /** 一行文字占多高（界面像素）；排版按它算一屏放得下几行。 */
    static int lineHeight() {
        return LINE_HEIGHT;
    }

    static void draw(GuiGraphics graphics, Font font, List<PanelLine> lines, int width) {
        if (lines.isEmpty()) return;
        int height = 0;
        for (PanelLine line : lines) height += line.separator() ? DIVIDER_HEIGHT : LINE_HEIGHT;
        int x = LEFT + PADDING;
        graphics.fill(LEFT, TOP, LEFT + PADDING * 2 + width, TOP + PADDING * 2 + height, BACKGROUND);
        int y = TOP + PADDING;
        for (PanelLine line : lines) {
            if (line.separator()) {
                graphics.fill(x, y + DIVIDER_HEIGHT / 2, x + width, y + DIVIDER_HEIGHT / 2 + 1, DIVIDER);
                y += DIVIDER_HEIGHT;
                continue;
            }
            if (line.label() != null) graphics.drawString(font, line.label(), x, y + 1, line.labelColor().argb());
            int pieceX = x + line.valueX();
            for (PanelLine.Piece piece : line.pieces()) {
                graphics.drawString(font, piece.text(), pieceX, y + 1, piece.color().argb());
                pieceX += font.width(piece.text());
            }
            y += LINE_HEIGHT;
        }
    }
}
