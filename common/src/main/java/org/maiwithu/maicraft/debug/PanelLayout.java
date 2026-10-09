// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 把一刻的现状排成面板上的一行行字。纯函数：给同样的现状、时刻、档和宽度，排出来的一定一样，离线就能测。
 *
 * <p>宽度由调用方按屏幕定好交进来，不随内容变，面板因此不会在直播画面上伸缩；文字在这个宽度里折行。
 * 标签栏宽度按这一档可能出现的最宽标签定，正文也不会左右跳。高度超出上限时，先让时间线里旧的条目让位，
 * 还放不下才从末尾截断。
 */
public final class PanelLayout {
    /** 截断处的记号：让人看得出后面还有。 */
    static final String ELLIPSIS = "…";
    /** 标签和正文之间空几个空格宽。 */
    static final String LABEL_GAP = "  ";

    /** 量一段文字画出来有多宽（界面像素）；游戏里用字体的宽度，离线测试用假的。 */
    public interface TextWidth {
        int width(String text);
    }

    private final TextWidth font;
    private final ZoneId zone;

    /**
     * @param font 量字宽
     * @param zone 事件时刻按哪个时区写成时:分:秒
     */
    public PanelLayout(TextWidth font, ZoneId zone) {
        this.font = Objects.requireNonNull(font, "font");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /**
     * 排出一屏。
     *
     * @param status    这一刻的现状
     * @param times     各状态第一次被看到的时刻
     * @param nowMillis 此刻的现实时间
     * @param level     哪一档；关着时什么都不排
     * @param page      详细档的哪一页
     * @param width     面板正文区宽度（界面像素）
     * @param maxLines  最多几行
     */
    public List<PanelLine> lay(StatusSnapshot status, FirstSeenTimes times, long nowMillis, PanelLevel level,
                               PanelPage page, int width, int maxLines) {
        if (level == PanelLevel.OFF) return List.of();
        Moment moment = new Moment(status, times, nowMillis, zone);
        List<Row> rows;
        List<String> labels;
        if (level == PanelLevel.BRIEF) {
            rows = BriefRows.rows(moment);
            labels = BriefRows.LABELS;
        } else if (page == PanelPage.RECENT_GOALS) {
            rows = GoalListRows.rows(moment);
            labels = List.of();
        } else {
            rows = FullRows.rows(moment);
            labels = FullRows.LABELS;
        }
        return fit(rows, labelColumn(labels), width, maxLines);
    }

    /** 排版出错时面板上只剩的那一行：说清是面板自己出了错，不影响角色干活。 */
    public List<PanelLine> failure(RuntimeException exception, int width) {
        Row row = Row.of(null, 2, "面板读取出错：" + exception.getClass().getSimpleName(), PanelColor.PROBLEM);
        return wrap(row, 0, width);
    }

    private int labelColumn(List<String> labels) {
        int widest = 0;
        for (String label : labels) widest = Math.max(widest, font.width(label));
        return widest == 0 ? 0 : widest + font.width(LABEL_GAP);
    }

    private List<PanelLine> fit(List<Row> rows, int labelColumn, int width, int maxLines) {
        List<List<PanelLine>> blocks = new ArrayList<>();
        int total = 0;
        for (Row row : rows) {
            List<PanelLine> block = wrap(row, labelColumn, width);
            blocks.add(block);
            total += block.size();
        }
        // 放不下：时间线里旧的条目先整条让位，从最旧的让起。
        for (int i = 0; i < blocks.size() && total > maxLines; i++) {
            if (rows.get(i).droppable()) {
                total -= blocks.get(i).size();
                blocks.set(i, List.of());
            }
        }
        List<PanelLine> lines = new ArrayList<>();
        blocks.forEach(lines::addAll);
        // 时间线让完还放不下：从末尾截掉，最后一行加记号。
        if (lines.size() > maxLines && maxLines > 0) {
            List<PanelLine> kept = new ArrayList<>(lines.subList(0, maxLines));
            PanelLine last = kept.getLast();
            kept.set(maxLines - 1, new PanelLine(last.label(), last.labelColor(), last.valueX(),
                    ellipsize(last.pieces(), width - last.valueX()), last.separator()));
            return kept;
        }
        return lines;
    }

    // 一行内容按正文区宽度折行；标签只写在第一行，续行与第一行的正文对齐；超出行数的以"…"结尾。
    private List<PanelLine> wrap(Row row, int labelColumn, int width) {
        if (row.divider()) return List.of(PanelLine.divider());
        int valueX = row.label() == null ? 0 : labelColumn;
        int available = Math.max(font.width("中") * 4, width - valueX);
        List<List<PanelLine.Piece>> broken = breakLines(row.value(), available);
        if (row.maxLines() != Row.UNLIMITED && broken.size() > row.maxLines()) {
            broken = new ArrayList<>(broken.subList(0, row.maxLines()));
            broken.set(broken.size() - 1, ellipsize(broken.getLast(), available));
        }
        List<PanelLine> lines = new ArrayList<>(broken.size());
        for (int i = 0; i < broken.size(); i++) {
            String label = i == 0 && row.label() != null && !row.label().isEmpty() ? row.label() : null;
            lines.add(new PanelLine(label, row.labelColor(), valueX, broken.get(i), false));
        }
        return lines;
    }

    // 逐字量宽度断行：中文没有空格可断，按字断；断行处的空格不带到下一行行首。
    private List<List<PanelLine.Piece>> breakLines(List<PanelLine.Piece> pieces, int available) {
        List<List<PanelLine.Piece>> lines = new ArrayList<>();
        List<PanelLine.Piece> current = new ArrayList<>();
        int used = 0;
        for (PanelLine.Piece piece : pieces) {
            String text = oneLine(piece.text());
            StringBuilder run = new StringBuilder();
            for (int offset = 0; offset < text.length(); ) {
                int codePoint = text.codePointAt(offset);
                offset += Character.charCount(codePoint);
                String glyph = new String(Character.toChars(codePoint));
                int glyphWidth = font.width(glyph);
                if (used + glyphWidth > available && used > 0) {
                    if (!run.isEmpty()) current.add(new PanelLine.Piece(run.toString(), piece.color()));
                    lines.add(current);
                    current = new ArrayList<>();
                    run.setLength(0);
                    used = 0;
                    if (glyph.equals(" ")) continue;
                }
                run.append(glyph);
                used += glyphWidth;
            }
            if (!run.isEmpty()) current.add(new PanelLine.Piece(run.toString(), piece.color()));
        }
        if (!current.isEmpty() || lines.isEmpty()) lines.add(current);
        return lines;
    }

    // 截到放得下末尾的"…"为止，记号沿用最后一段的颜色。
    private List<PanelLine.Piece> ellipsize(List<PanelLine.Piece> line, int available) {
        List<PanelLine.Piece> pieces = new ArrayList<>(line);
        int ellipsis = font.width(ELLIPSIS);
        while (!pieces.isEmpty() && width(pieces) + ellipsis > available) {
            PanelLine.Piece last = pieces.removeLast();
            String text = last.text();
            if (text.length() > 1) {
                int cut = text.offsetByCodePoints(text.length(), -1);
                pieces.add(new PanelLine.Piece(text.substring(0, cut), last.color()));
            }
        }
        PanelColor color = pieces.isEmpty() ? PanelColor.TEXT : pieces.getLast().color();
        pieces.add(new PanelLine.Piece(ELLIPSIS, color));
        return pieces;
    }

    private int width(List<PanelLine.Piece> pieces) {
        int total = 0;
        for (PanelLine.Piece piece : pieces) total += font.width(piece.text());
        return total;
    }

    // 折行和量宽前把换行、制表与连续空白压成一个空格：LLM 写的 purpose、游戏里的消息都可能带换行。
    private static String oneLine(String text) {
        return text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').replaceAll(" {2,}", " ");
    }
}
