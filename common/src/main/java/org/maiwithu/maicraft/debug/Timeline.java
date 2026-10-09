// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.maiwithu.maicraft.kernel.event.TaskEvent;

/**
 * 详细档底部"最近发生的"：LLM 发来的工具调用和任务事件按时间排在一条线上，
 * 一眼看得出"发了什么、成没成、角色怎么反应"。
 *
 * <p>同一个工具连着调用、结果也一样（都成功，或报同一种错）时合成一行写 ×N：宿主每隔几十秒挂一次
 * events 等新事件，不合并的话整条线都是它。调用的结果写在参数前面，参数太长被截断时结果也看得见。
 */
final class Timeline {
    /** 最多留几行：放不下时旧的先让位。 */
    static final int MAX_ENTRIES = 12;
    /** 一条最多折三行。 */
    static final int ENTRY_LINES = 3;

    private Timeline() {}

    static List<Row> rows(Moment moment) {
        List<Entry> entries = new ArrayList<>();
        for (TaskEvent event : moment.status().events()) {
            entries.add(new Entry(moment.times().seenAt(FirstSeenTimes.event(event), moment.nowMillis()), 1, event, null, 1));
        }
        for (StatusSnapshot.ToolCall call : moment.status().calls()) {
            // 同一刻进来的调用排在它引起的事件前面：先有 execute，才有"开始"。
            entries.add(new Entry(call.startedAtMillis(), 0, null, call, 1));
        }
        entries.sort(Comparator.comparingLong(Entry::atMillis).thenComparingInt(Entry::order));
        List<Entry> merged = mergeRepeats(entries);
        List<Row> rows = new ArrayList<>();
        for (Entry entry : merged.subList(Math.max(0, merged.size() - MAX_ENTRIES), merged.size())) {
            rows.add(row(moment, entry).labelColored(PanelColor.MINOR).whenRoomAllows());
        }
        return rows;
    }

    // 连着的同一工具、同样结果合成一条，留最近那一次的参数与时刻，记下一共几次。
    private static List<Entry> mergeRepeats(List<Entry> entries) {
        List<Entry> merged = new ArrayList<>();
        for (Entry entry : entries) {
            Entry last = merged.isEmpty() ? null : merged.getLast();
            if (last != null && last.call() != null && entry.call() != null && sameKind(last.call(), entry.call())) {
                merged.set(merged.size() - 1, new Entry(entry.atMillis(), entry.order(), null, entry.call(), last.repeats() + 1));
            } else {
                merged.add(entry);
            }
        }
        return merged;
    }

    // 同一类：同一个工具，并且都成功、都报同一种错，或者前一次成功、这一次还挂着（events 一次接一次地挂）。
    private static boolean sameKind(StatusSnapshot.ToolCall earlier, StatusSnapshot.ToolCall later) {
        if (!earlier.tool().equals(later.tool())) return false;
        if (later.running()) return earlier.succeeded() || earlier.running();
        if (earlier.running()) return false;
        return earlier.succeeded() ? later.succeeded()
                : !later.succeeded() && earlier.errorCode().equals(later.errorCode());
    }

    private static Row row(Moment moment, Entry entry) {
        String clock = moment.clock(entry.atMillis());
        return entry.event() != null ? eventRow(clock, entry.event()) : callRow(clock, entry.call(), entry.repeats());
    }

    // 调用：→ 工具 ×N 结果 参数；结果在参数前面，参数长了被截断也看得到成没成。
    private static Row callRow(String clock, StatusSnapshot.ToolCall call, int repeats) {
        List<PanelLine.Piece> pieces = new ArrayList<>();
        pieces.add(new PanelLine.Piece("→ ", PanelColor.MINOR));
        pieces.add(new PanelLine.Piece(call.tool(), PanelColor.TEXT));
        if (repeats > 1) pieces.add(new PanelLine.Piece(" ×" + repeats, PanelColor.MINOR));
        if (call.running()) {
            pieces.add(new PanelLine.Piece(" 处理中", PanelColor.WAITING));
        } else if (call.succeeded()) {
            pieces.add(new PanelLine.Piece(" 成功", PanelColor.GOOD));
        } else {
            pieces.add(new PanelLine.Piece(" 报错 " + call.errorCode() + " " + StatusSentences.error(call), PanelColor.PROBLEM));
        }
        pieces.add(new PanelLine.Piece(" " + call.arguments(), PanelColor.MINOR));
        return Row.of(clock, ENTRY_LINES, pieces);
    }

    // 任务事件：种类 #目标编号 消息；种类的颜色跟着意思走。
    private static Row eventRow(String clock, TaskEvent event) {
        List<PanelLine.Piece> pieces = new ArrayList<>();
        pieces.add(new PanelLine.Piece(PanelWords.eventKind(event.kind()), color(event)));
        if (event.goalRunId() >= 0) pieces.add(new PanelLine.Piece(" #" + event.goalRunId(), PanelColor.MINOR));
        pieces.add(new PanelLine.Piece(" " + event.message(), PanelColor.TEXT));
        return Row.of(clock, ENTRY_LINES, pieces);
    }

    private static PanelColor color(TaskEvent event) {
        return switch (event.kind()) {
            case STARTED, RESUMED -> PanelColor.DOING;
            case ASKED, PAUSED, TEMPORARY_TASK_STARTED, NEED_UNHANDLED -> PanelColor.WAITING;
            case CHARACTER_DIED -> PanelColor.PROBLEM;
            case STEP_FINISHED, TEMPORARY_TASK_FINISHED, DEATH_RECOVERY_APPLIED -> PanelColor.MINOR;
            case FINISHED -> event.status() == null ? PanelColor.MINOR : switch (event.status()) {
                case DONE -> PanelColor.GOOD;
                case PARTIAL -> PanelColor.WAITING;
                case FAILED -> PanelColor.PROBLEM;
                case CANCELLED -> PanelColor.MINOR;
            };
        };
    }

    /** 时间线上的一条：一条任务事件，或一次（连着的几次）工具调用。 */
    private record Entry(long atMillis, int order, TaskEvent event, StatusSnapshot.ToolCall call, int repeats) {}
}
