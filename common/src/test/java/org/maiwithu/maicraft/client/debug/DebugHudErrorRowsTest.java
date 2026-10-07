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
 * 面板宽度是唯一口径：固定行与未折行正文共同定宽（下限 160、上限屏宽预算），事件与报错按这一宽度折行，
 * 续行没有前缀、按整个面板宽折行，右缘因此与背景框一致；长 token 不被词中硬切。
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
        continuationLinesFillPanelWidthAfterWidePrefix();
        contentDemandWidensPanelToFinalWrapWidth();
        latestEventLineCollapsesNewestEventToSingleRow();
        perfTextFollowsTickBudget();
        perfColorSeparatesThreeSpeedBands();
        System.out.println("DebugHudErrorRowsTest: latest error rows wrap, truncate and stay visible; panel width is the single wrap-and-box source widened by unwrapped content; continuation lines fill the panel width; perf row follows tick budget");
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
            // 分词器在词边界断行时允许少量超出（原版 splitLines 行为）。
            check(width <= 100 + 10, "every wrapped line stays within the panel width budget");
        }
    }

    // 长前缀事件（时间戳+长类型名）把面板撑宽后，续行没有前缀、必须按整个面板宽折行，
    // 而不是再扣一次前缀后的残余宽度——这是续行右缘与背景框右缘对齐的断言。
    private static void continuationLinesFillPanelWidthAfterWidePrefix() {
        IntentRuntime.AttentionItem event = new IntentRuntime.AttentionItem(
                Instant.now(), "a_very_long_event_type_name", "task", "word ".repeat(60).strip());
        DebugHudController.EventLine[] lines = wrapEvent(SPLITTER, event, 200);
        check(lines.length > 1, "a long-prefix event must wrap its message into continuation lines");
        for (int i = 1; i < lines.length; i++) {
            int width = lines[i].segments().stream()
                    .mapToInt(segment -> segment.text().length()).sum();
            check(width >= 100, "continuation lines must fill the panel width, not the prefix-deducted residue");
        }
    }

    // 未折行正文的自然宽度参与定宽：长事件取前缀+全文、报错取标签+全文，撑到屏宽预算即止；
    // 没有长内容时面板宽不因这一步改变。
    private static void contentDemandWidensPanelToFinalWrapWidth() {
        check(DebugHudController.panelWidthWithContent(SPLITTER, 160, List.of(), null, 1000) == 160,
                "no pending content leaves the fixed-row panel width untouched");
        IntentRuntime.AttentionItem event = new IntentRuntime.AttentionItem(
                Instant.now(), "type", "task", "m".repeat(300));
        check(DebugHudController.panelWidthWithContent(SPLITTER, 160, List.of(event), null, 1000) == 9 + 6 + 300,
                "a long event widens the panel to prefix plus full unwrapped message width");
        check(DebugHudController.panelWidthWithContent(SPLITTER, 160, List.of(event), null, 200) == 200,
                "unwrapped content demand clamps to the screen budget");
        check(DebugHudController.panelWidthWithContent(SPLITTER, 160, List.of(), "m".repeat(300), 1000) == 6 + 300,
                "a pending error widens the panel to label plus full unwrapped message width");
    }

    // 单行档：升序列表（最旧在前）取末尾最新事件，压成恰好一行；长消息以 … 收尾，短消息原样，空列表整段缺席。
    private static void latestEventLineCollapsesNewestEventToSingleRow() {
        check(DebugHudController.latestEventLine(SPLITTER, List.of(), 200).isEmpty(),
                "no events leaves the single-line feed empty");
        IntentRuntime.AttentionItem older = new IntentRuntime.AttentionItem(
                Instant.now(), "older_type", "task", "old event body");
        IntentRuntime.AttentionItem newest = new IntentRuntime.AttentionItem(
                Instant.now(), "newest_type", "task", "word ".repeat(60).strip());
        List<DebugHudController.EventLine> lines = DebugHudController.latestEventLine(
                SPLITTER, List.of(older, newest), 200);
        check(lines.size() == 1, "the single-line feed renders exactly one row");
        String joined = joinSegments(lines.getFirst());
        check(joined.contains("newest_type") && !joined.contains("old event body"),
                "the single-line feed shows the newest (last) event only");
        check(joined.endsWith("…"), "an overlong message truncates within the single row");

        IntentRuntime.AttentionItem shortEvent = new IntentRuntime.AttentionItem(
                Instant.now(), "tick", "world", "short body");
        List<DebugHudController.EventLine> single = DebugHudController.latestEventLine(
                SPLITTER, List.of(shortEvent), 200);
        check(single.size() == 1, "a short event still renders exactly one row");
        String shortJoined = joinSegments(single.getFirst());
        check(shortJoined.contains("short body") && !shortJoined.endsWith("…"),
                "a short message stays whole without an ellipsis");
    }

    private static String joinSegments(DebugHudController.EventLine line) {
        return line.segments().stream().map(DebugHudController.Segment::text)
                .collect(Collectors.joining());
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
