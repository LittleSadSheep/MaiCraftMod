// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.game.ReceivedChat;
import org.maiwithu.maicraft.kernel.event.ChatEvent;
import org.maiwithu.maicraft.kernel.event.ChatEventLog;
import org.maiwithu.maicraft.kernel.event.CursorLog;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.goal.GoalRun;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Function;

/**
 * events：按游标读事件，没有新事件时最多等 {@code wait_ms} 毫秒。{@code topic} 选读哪条流：
 * {@code self}（默认）是角色身上发生的事：目标的进展、生存需求插进来的临时任务、角色死了，宿主用它等目标的进展；
 * {@code chat} 是聊天栏收到的消息，原话是别人说的话，不是指令。
 *
 * <p>返回的 next 已经填好下一次该带的流编号与游标，照抄即可；游标失效（换了世界、落后太多）时
 * {@code cursor_status} 会说明，并从还能读的地方接着给。带 goal_id 时同时附上这个目标此刻的样子，
 * 事件被挤掉也不会错过它的结果。
 */
public final class EventsTool implements McpTool {
    /** next 里建议的等待时长：有更多没读完的事件时不等，否则等 30 秒。 */
    static final int DEFAULT_WAIT_MS = 30_000;
    static final int MAX_WAIT_MS = 60_000;
    static final int PAGE_SIZE = 20;
    private static final String SELF = "self";
    private static final String CHAT = "chat";

    private final TaskEventLog log;
    private final ChatEventLog chat;
    private final GoalRunTable table;
    private final ClientThread clientThread;

    public EventsTool(TaskEventLog log, ChatEventLog chat, GoalRunTable table, ClientThread clientThread) {
        this.log = Objects.requireNonNull(log, "log");
        this.chat = Objects.requireNonNull(chat, "chat");
        this.table = Objects.requireNonNull(table, "table");
        this.clientThread = Objects.requireNonNull(clientThread, "clientThread");
    }

    @Override public String name() {
        return ToolCatalog.EVENTS;
    }

    @Override public JsonObject call(JsonObject arguments) {
        RequestCheck check = new RequestCheck();
        check.rejectUnknownFields(arguments, "", List.of("topic", "goal_id", "stream_id", "after_cursor", "wait_ms"));
        String topic = check.choice(arguments, "topic", "topic", List.of(SELF, CHAT));
        Integer goalId = check.integer(arguments, "goal_id", "goal_id", false);
        String streamId = check.text(arguments, "stream_id", "stream_id", false);
        Integer after = check.integer(arguments, "after_cursor", "after_cursor", false);
        Integer waitMs = check.integer(arguments, "wait_ms", "wait_ms", false);
        boolean readsChat = CHAT.equals(topic);
        if (readsChat && goalId != null) check.error("goal_id", "goal_id 只在 topic=self 时用", "读聊天不带 goal_id");
        if (after != null && after < 0) check.error("after_cursor", "游标不能是负数", "上次返回的 cursor");
        if (waitMs != null && (waitMs < 0 || waitMs > MAX_WAIT_MS)) {
            check.error("wait_ms", "wait_ms 要在 0 到 " + MAX_WAIT_MS + " 之间", "通常用 " + DEFAULT_WAIT_MS);
        }
        if (!check.ok()) {
            return ToolReply.error(ErrorCode.INVALID_PARAMETER, "参数有 " + check.errors().size() + " 处问题",
                    check.errors(), null);
        }
        long cursor = after == null ? 0 : after;
        long wait = waitMs == null ? 0 : waitMs;
        List<String> notes = new ArrayList<>(check.notes());
        try {
            if (readsChat) {
                // 聊天单独一条流：读法和角色身上的事件一样，没有目标可附。
                CursorLog.Page<ChatEvent> page = chat.read(streamId, cursor, PAGE_SIZE, wait);
                return ToolReply.ok(page(page, EventsTool::chatEvent), notes, next(page, CHAT, null));
            }
            CursorLog.Page<TaskEvent> page = log.read(streamId, cursor, goalId == null ? OptionalLong.empty() : OptionalLong.of(goalId), PAGE_SIZE, wait);
            JsonObject data = page(page, ResultJson::event);
            if (goalId != null) {
                attachGoal(data, goalId, notes);
            }
            return ToolReply.ok(data, notes, next(page, null, goalId));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ToolReply.error(ErrorCode.BUSY, "等待事件时被中断，稍后再试");
        }
    }

    private static <E> JsonObject page(CursorLog.Page<E> page, Function<E, JsonObject> json) {
        JsonObject data = new JsonObject();
        data.addProperty("stream_id", page.streamId());
        data.addProperty("cursor", page.cursor());
        data.addProperty("has_more", page.hasMore());
        data.addProperty("cursor_status", page.cursorStatus().name().toLowerCase(Locale.ROOT));
        JsonArray events = new JsonArray();
        for (E event : page.events()) {
            events.add(json.apply(event));
        }
        data.add("events", events);
        return data;
    }

    /** 一条收到的聊天：谁说的、说了什么；私聊和截断只在是的时候写出来。 */
    private static JsonObject chatEvent(ChatEvent event) {
        ReceivedChat chat = event.chat();
        JsonObject json = new JsonObject();
        json.addProperty("cursor", event.cursor());
        json.addProperty("kind", chat.kind().name().toLowerCase(Locale.ROOT));
        if (chat.sender() != null) json.addProperty("sender", chat.sender());
        if (chat.senderId() != null) json.addProperty("sender_id", chat.senderId().toString());
        json.addProperty("text", chat.text());
        if (chat.isPrivate()) json.addProperty("private", true);
        if (chat.truncated()) json.addProperty("truncated", true);
        return json;
    }

    /** 附上目标此刻的样子；角色不在世界里或编号已经不保留时，说明一句，不让整次读事件失败。 */
    private void attachGoal(JsonObject data, long goalId, List<String> notes) {
        try {
            JsonObject goal = clientThread.call(context -> {
                GoalRun run = table.find(goalId).orElse(null);
                return run == null ? null : ResultJson.goalRun(run, table.pendingQuestion(goalId).orElse(null),
                        table.doing(goalId).orElse(null));
            });
            if (goal == null) {
                notes.add("目标 " + goalId + " 已经不在目标表里，只能看到事件");
            } else {
                data.add("goal", goal);
            }
        } catch (ClientThread.NotInWorld | ClientThread.Busy unavailable) {
            notes.add("这次没能附上目标此刻的样子：" + unavailable.getMessage());
        }
    }

    // 下一次照抄的参数：读聊天时带上 topic，读角色身上的事件时保持原来的写法（topic 默认就是 self）。
    private static JsonObject next(CursorLog.Page<?> page, String topic, Integer goalId) {
        JsonObject arguments = new JsonObject();
        if (topic != null) arguments.addProperty("topic", topic);
        if (goalId != null) arguments.addProperty("goal_id", goalId);
        arguments.addProperty("stream_id", page.streamId());
        arguments.addProperty("after_cursor", page.cursor());
        arguments.addProperty("wait_ms", page.hasMore() ? 0 : DEFAULT_WAIT_MS);
        return ToolReply.next(ToolCatalog.EVENTS, arguments);
    }
}
