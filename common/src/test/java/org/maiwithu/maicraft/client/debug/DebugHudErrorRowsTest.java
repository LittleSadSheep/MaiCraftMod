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
import static org.maiwithu.maicraft.client.debug.DebugHudController.perfColor;
import static org.maiwithu.maicraft.client.debug.DebugHudController.perfText;

/** 最新报错行必须按面板宽度折成多行并守住与事件区相同的 4 行上限；空报错落"未知"，不能回到单行截断。
 * 性能行在同一套件覆盖：TPS 按目标单刻预算折算（预算内满速、超出 1000÷MSPT 掉速），配色分满速/轻度/明显三档。 */
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
        perfTextFollowsTickBudget();
        perfColorSeparatesThreeSpeedBands();
        System.out.println("DebugHudErrorRowsTest: latest error rows wrap, truncate and stay visible; perf row follows tick budget");
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

    // 预算内（含边界）给满速 1000÷目标，超出按 1000÷MSPT 等比掉速；目标随 tick rate 走，不写死 50/20。
    private static void perfTextFollowsTickBudget() {
        check(perfText(12.3, 50.0).equals("TPS 20.0 · MSPT 12.3ms"),
                "within budget the TPS is the full 1000÷target");
        check(perfText(50.0, 50.0).equals("TPS 20.0 · MSPT 50.0ms"),
                "the budget boundary itself still counts as full speed");
        check(perfText(80.0, 50.0).equals("TPS 12.5 · MSPT 80.0ms"),
                "beyond the budget TPS degrades as 1000÷MSPT");
        check(perfText(20.0, 25.0).equals("TPS 40.0 · MSPT 20.0ms"),
                "the full speed value follows the target, not a hardcoded 20");
    }

    // 三档配色：目标内绿、目标 1.25 倍内黄、再往上是明显的掉速红。
    private static void perfColorSeparatesThreeSpeedBands() {
        check(perfColor(30.0, 50.0) == ChatFormatting.GREEN, "within budget stays green");
        check(perfColor(60.0, 50.0) == ChatFormatting.YELLOW,
                "a light overrun (up to 1.25× target) turns yellow");
        check(perfColor(70.0, 50.0) == ChatFormatting.RED,
                "a heavy overrun beyond 1.25× target turns red");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
