// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.debug;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.StringSplitter;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.debug.DebugHudController.Row;
import static org.maiwithu.maicraft.client.debug.DebugHudController.errorRows;
import static org.maiwithu.maicraft.client.debug.DebugHudController.panelWidth;

/** 最新报错行必须按面板宽度折成多行并守住与事件区相同的 4 行上限；空报错落"未知"，不能回到单行截断。 */
public final class DebugHudErrorRowsTest {
    /** 假宽度函数：一个码点一像素，行数断言只取决于消息长度与给定宽度，不依赖真实字体。 */
    private static final StringSplitter SPLITTER =
            new StringSplitter((codePoint, style) -> 1.0f);

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        longErrorWrapsIntoMultipleRows();
        overlongErrorTruncatesAtLineBudget();
        shortErrorStaysSingleRow();
        blankErrorFallsBackToUnknown();
        panelWidthFollowsWidestRow();
        System.out.println("DebugHudErrorRowsTest: latest error rows wrap, truncate and stay visible");
    }

    // 60 个 7 字符单词（约 480 像素）在 200 像素行宽下至少占三行：多行展示且未触及截断。
    private static void longErrorWrapsIntoMultipleRows() {
        List<Row> rows = errorRows("abcdefg ".repeat(60).strip(), 200, SPLITTER);
        check(rows.size() >= 3, "a wide error message must occupy at least three panel rows");
        check(rows.size() <= 4, "error rows must respect the shared four-line budget");
        check(rows.getFirst().label().equals("最新报错"), "the first row carries the label");
        for (int i = 1; i < rows.size(); i++) {
            check(rows.get(i).label().isEmpty(), "continuation rows must not repeat the label");
        }
        check(!rows.getLast().value().endsWith("…"), "a message within the budget must not be truncated");
    }

    // 约 1800 像素的消息远超 4 行预算：恰好四行、末行以 … 收尾，且全部保持问题红色。
    private static void overlongErrorTruncatesAtLineBudget() {
        List<Row> rows = errorRows("abcdefgh lmnopqrs ".repeat(100).strip(), 200, SPLITTER);
        check(rows.size() == 4, "truncation must stop at the shared four-line budget");
        check(rows.getLast().value().endsWith("…"), "truncated content must be announced with an ellipsis");
        check(rows.stream().allMatch(row -> row.color() == ChatFormatting.RED),
                "every error row keeps the red problem color");
    }

    private static void shortErrorStaysSingleRow() {
        List<Row> rows = errorRows("bucket missing", 200, SPLITTER);
        check(rows.size() == 1 && rows.getFirst().value().equals("bucket missing"),
                "a short message must render as one unmodified row");
    }

    private static void blankErrorFallsBackToUnknown() {
        for (String blank : new String[]{null, "", "   "}) {
            List<Row> rows = errorRows(blank, 200, SPLITTER);
            check(rows.size() == 1 && rows.getFirst().value().equals("未知"),
                    "a blank error must stay visible as 未知 instead of an empty row");
        }
    }

    private static void panelWidthFollowsWidestRow() {
        check(panelWidth(List.of(), SPLITTER) == 160, "the panel width has a 160px floor");
        List<Row> rows = List.of(new Row("任务", "空闲", ChatFormatting.GRAY),
                new Row("", "r".repeat(180), ChatFormatting.AQUA));
        check(panelWidth(rows, SPLITTER) == 180,
                "the panel width follows the widest row, labeled or not");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
