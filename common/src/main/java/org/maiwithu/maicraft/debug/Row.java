// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.util.List;

/**
 * 排版前的一行：标签、正文几段、最多折几行。折行和对齐由 {@link PanelLayout} 统一做，各段只管说什么。
 *
 * @param label      行首标签；空串表示接在上一行下面、和正文对齐的续行；null 表示从面板左边起、不留标签栏
 * @param labelColor 标签的颜色
 * @param value      正文
 * @param maxLines   最多折几行，超出的以"…"结尾；{@link #UNLIMITED} 表示不限（提问与选项是应答依据，不截断）
 * @param droppable  面板放不下时可以整行让位（时间线里旧的那些）
 * @param divider    段与段之间的分隔线
 */
record Row(String label, PanelColor labelColor, List<PanelLine.Piece> value, int maxLines,
           boolean droppable, boolean divider) {

    /** 不限行数。 */
    static final int UNLIMITED = 0;

    Row {
        value = List.copyOf(value);
    }

    /** 带标签的一行。 */
    static Row of(String label, int maxLines, List<PanelLine.Piece> value) {
        return new Row(label, PanelColor.LABEL, value, maxLines, false, false);
    }

    /** 带标签、只有一段同色文字的一行。 */
    static Row of(String label, int maxLines, String text, PanelColor color) {
        return of(label, maxLines, List.of(new PanelLine.Piece(text, color)));
    }

    /** 段与段之间的分隔线。 */
    static Row dividerLine() {
        return new Row(null, PanelColor.LABEL, List.of(), 1, false, true);
    }

    /** 同一行，但放不下时可以让位。 */
    Row whenRoomAllows() {
        return new Row(label, labelColor, value, maxLines, true, divider);
    }

    /** 同一行，标签换个颜色（时间线的时刻用次要色）。 */
    Row labelColored(PanelColor color) {
        return new Row(label, color, value, maxLines, droppable, divider);
    }
}
