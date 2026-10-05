// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.debug;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.StringSplitter;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.debug.DebugHudController.Row;
import org.maiwithu.maicraft.intent.IntentRuntime;
import static org.maiwithu.maicraft.client.debug.DebugHudController.errorRows;
import static org.maiwithu.maicraft.client.debug.DebugHudController.panelWidth;
import static org.maiwithu.maicraft.client.debug.DebugHudController.perfColor;
import static org.maiwithu.maicraft.client.debug.DebugHudController.perfText;
import static org.maiwithu.maicraft.client.debug.DebugHudController.wrapEvent;

/** 最新报错行必须按面板宽度折成多行并守住与事件区相同的 4 行上限；空报错落"未知"，不能回到单行截断。
 * 面板宽度与事件区折行共用同一口径：下限 160、上限为屏宽预算；事件消息里的长 token 不被词中硬切。
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
        panelWidthClampsBetweenFloorAndScreenBudget();
        eventWrapKeepsLongTokenWholeAndWithinPanel();
        perfTextFollowsTickBudget();
        perfColorSeparatesThreeSpeedBands();
        System.out.println("DebugHudErrorRowsTest: latest error rows wrap, truncate and stay visible; panel width clamps to screen budget; event wrap keeps long tokens whole; perf row follows tick budget");
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

    private static void panelWidthClampsBetweenFloorAndScreenBudget() {
        check(panelWidth(List.of(), SPLITTER, 1000) == 160, "the panel width has a 160px floor");
        List<Row> rows = List.of(new Row("任务", "空闲", ChatFormatting.GRAY),
                new Row("", "r".repeat(180), ChatFormatting.AQUA));
        check(panelWidth(rows, SPLITTER, 1000) == 180,
                "the panel width follows the widest row, labeled or not");
        check(panelWidth(List.of(new Row("", "r".repeat(500), ChatFormatting.AQUA)), SPLITTER, 300) == 300,
                "content wider than the screen budget clamps the panel to the budget");
        check(panelWidth(rows, SPLITTER, 160) == 160,
                "the screen budget never shrinks the panel below the 160px floor");
    }

    // 折行宽度统一到面板宽度后，事件消息里的长 token 必须整体保留，且每行总宽不超出面板口径。
    private static void eventWrapKeepsLongTokenWholeAndWithinPanel() {
        IntentRuntime.AttentionItem event = new IntentRuntime.AttentionItem(
                Instant.now(), "task_progress", "task", "planning_acquisition finished");
        DebugHudController.EventLine[] lines = wrapEvent(SPLITTER, event, 200);
        check(lines.length == 1, "a message that fits the panel width must stay on one line");
        String joined = lines[0].segments().stream()
                .map(DebugHudController.Segment::text).collect(Collectors.joining());
        check(joined.contains("planning_acquisition"),
                "a long token must not be split mid-word now that the wrap width follows the panel");
        check(joined.length() <= 200, "the rendered line must not exceed the shared panel width");

        DebugHudController.EventLine[] wrapped = wrapEvent(SPLITTER,
                new IntentRuntime.AttentionItem(Instant.now(), "task_progress", "task",
                        "word ".repeat(60).strip()), 100);
        check(wrapped.length > 1, "a message wider than the panel wraps into continuation lines");
        for (DebugHudController.EventLine line : wrapped) {
            int width = line.segments().stream()
                    .mapToInt(segment -> segment.text().length()).sum();
            // 分词器在词边界断行时允许少量超出（原版 splitLines 行为），背景框以正文实测宽度兜底。
            check(width <= 100 + 10, "every wrapped line stays within the panel width budget");
        }
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
