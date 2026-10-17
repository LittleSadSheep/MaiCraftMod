// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.util.List;
import java.util.Objects;

/**
 * 排好的一行：行首标签（续行没有标签），从 {@code valueX} 起按顺序画的几段彩色文字；
 * 或者段与段之间的一条分隔线。渲染只照着画，不做任何判断；一行的宽度在排版时已经量好，不会超出面板。
 *
 * @param label      行首标签；续行与不带标签的行为 null
 * @param labelColor 标签的颜色；时间线的时刻用次要色，其余用标签色
 * @param valueX     正文从离面板左边多少像素处开始画；同一块里的续行与首行对齐
 * @param pieces     正文的几段文字
 * @param separator  这是段与段之间的分隔线，没有文字
 */
public record PanelLine(String label, PanelColor labelColor, int valueX, List<Piece> pieces, boolean separator) {

    public PanelLine {
        pieces = List.copyOf(pieces);
        labelColor = labelColor == null ? PanelColor.LABEL : labelColor;
    }

    /** 段与段之间的分隔线。 */
    static PanelLine divider() {
        return new PanelLine(null, PanelColor.LABEL, 0, List.of(), true);
    }

    /** 这一行的正文连起来，离线测试拿它断言。 */
    public String text() {
        StringBuilder text = new StringBuilder();
        for (Piece piece : pieces) text.append(piece.text());
        return text.toString();
    }

    /** 一段同色的文字。 */
    public record Piece(String text, PanelColor color) {
        public Piece {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(color, "color");
        }
    }
}
