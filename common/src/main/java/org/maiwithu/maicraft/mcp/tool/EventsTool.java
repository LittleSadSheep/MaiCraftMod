// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.goal.GoalRun;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * events：按游标读任务事件，没有新事件时最多等 {@code wait_ms} 毫秒。宿主用它等任务的进展，
 * Amaidesu 一侧把这些事件叫 attention。
 *
 * <p>返回的 next 已经填好下一次该带的流编号与游标，照抄即可；游标失效（换了世界、落后太多）时
 * {@code cursor_status} 会说明，并从还能读的地方接着给。带 task_id 时同时附上这个任务此刻的样子，
 * 事件被挤掉也不会错过它的结果。
 */
public final class EventsTool implements McpTool {
    /** next 里建议的等待时长：有更多没读完的事件时不等，否则等 30 秒。 */
    static final int DEFAULT_WAIT_MS = 30_000;
    static final int MAX_WAIT_MS = 60_000;
    static final int PAGE_SIZE = 20;

    private final TaskEventLog log;
    private final GoalRunTable table;
    private final ClientThread clientThread;

    public EventsTool(TaskEventLog log, GoalRunTable table, ClientThread clientThread) {
        this.log = Objects.requireNonNull(log, "log");
        this.table = Objects.requireNonNull(table, "table");
        this.clientThread = Objects.requireNonNull(clientThread, "clientThread");
    }

    @Override public String name() {
        return ToolCatalog.EVENTS;
    }

    @Override public JsonObject call(JsonObject arguments) {
        RequestCheck check = new RequestCheck();
        check.rejectUnknownFields(arguments, "", List.of("task_id", "stream_id", "after_cursor", "wait_ms"));
        Integer taskId = check.integer(arguments, "task_id", "task_id", false);
        String streamId = check.text(arguments, "stream_id", "stream_id", false);
        Integer after = check.integer(arguments, "after_cursor", "after_cursor", false);
        Integer waitMs = check.integer(arguments, "wait_ms", "wait_ms", false);
        if (after != null && after < 0) check.error("after_cursor", "游标不能是负数", "上次返回的 cursor");
        if (waitMs != null && (waitMs < 0 || waitMs > MAX_WAIT_MS)) {
            check.error("wait_ms", "wait_ms 要在 0 到 " + MAX_WAIT_MS + " 之间", "通常用 " + DEFAULT_WAIT_MS);
        }
        if (!check.ok()) {
            return ToolReply.error(ErrorCode.INVALID_PARAMETER, "参数有 " + check.errors().size() + " 处问题",
                    check.errors(), null);
        }
        TaskEventLog.Page page;
        try {
            page = log.read(streamId, after == null ? 0 : after, taskId == null ? -1 : taskId, PAGE_SIZE,
                    waitMs == null ? 0 : waitMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ToolReply.error(ErrorCode.BUSY, "等待事件时被中断，稍后再试");
        }
        List<String> notes = new ArrayList<>(check.notes());
        JsonObject data = page(page);
        if (taskId != null) {
            attachTask(data, taskId, notes);
        }
        return ToolReply.ok(data, notes, next(page, taskId));
    }

    private static JsonObject page(TaskEventLog.Page page) {
        JsonObject data = new JsonObject();
        data.addProperty("stream_id", page.streamId());
        data.addProperty("cursor", page.cursor());
        data.addProperty("has_more", page.hasMore());
        data.addProperty("cursor_status", page.cursorStatus().name().toLowerCase(Locale.ROOT));
        JsonArray events = new JsonArray();
        for (TaskEvent event : page.events()) {
            events.add(ResultJson.event(event));
        }
        data.add("events", events);
        return data;
    }

    /** 附上任务此刻的样子；角色不在世界里或编号已经不保留时，说明一句，不让整次读事件失败。 */
    private void attachTask(JsonObject data, long taskId, List<String> notes) {
        try {
            JsonObject task = clientThread.call(context -> {
                GoalRun run = table.find(taskId).orElse(null);
                return run == null ? null : ResultJson.goalRun(run, table.pendingQuestion(taskId).orElse(null),
                        table.doing(taskId).orElse(null));
            });
            if (task == null) {
                notes.add("任务 " + taskId + " 已经不在任务表里，只能看到事件");
            } else {
                data.add("task", task);
            }
        } catch (ClientThread.NotInWorld | ClientThread.Busy unavailable) {
            notes.add("这次没能附上任务此刻的样子：" + unavailable.getMessage());
        }
    }

    private static JsonObject next(TaskEventLog.Page page, Integer taskId) {
        JsonObject arguments = new JsonObject();
        if (taskId != null) arguments.addProperty("task_id", taskId);
        arguments.addProperty("stream_id", page.streamId());
        arguments.addProperty("after_cursor", page.cursor());
        arguments.addProperty("wait_ms", page.hasMore() ? 0 : DEFAULT_WAIT_MS);
        return ToolReply.next(ToolCatalog.EVENTS, arguments);
    }
}
