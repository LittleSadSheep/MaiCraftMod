// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.UUID;

/** 记住最近发生的二百五十六条消息，例如任务开始、需要回答、玩家受伤；任务完整进度另存在任务单中。游标、分页与订阅机制见 {@link BoundedMessageStream}，按任务过滤的规则见 matches。 */
final class AttentionFeed extends BoundedMessageStream {
    @Override protected String pageKey() { return "events"; }
    @Override protected String label() { return "attention"; }

    void publish(String type, UUID taskId, String message, JsonObject data) {
        // 每条消息给一个递增编号并复制内容；满了就移走最旧消息，最后通知正在等新消息的调用者。
        JsonObject signal;
        synchronized (this) {
            JsonObject event = new JsonObject();
            event.addProperty("cursor", nextCursor());
            event.addProperty("type", type);
            event.addProperty("timestamp", Instant.now().toString());
            if (taskId != null) event.addProperty("task_id", taskId.toString());
            event.addProperty("priority", taskId != null ? "task" : background(type) ? "background" : "important");
            event.addProperty("message", message == null ? "" : message);
            event.add("data", data == null ? new JsonObject() : data.deepCopy());
            store(event);
            signal = readPage(currentCursor() - 1, 1, streamId(), item -> true);
        }
        notifyListeners(signal);
    }

    synchronized JsonObject read(long after, int limit, String expectedStream, UUID taskId) {
        return readPage(after, limit, expectedStream, event -> matches(event, taskId));
    }

    private static boolean matches(JsonObject event, UUID taskId) {
        // 指定任务时只看它的任务消息，但受伤、死亡、应急等重要事件仍要通知，不能因为只盯一个任务而漏掉。
        if (taskId == null) return true;
        if (event.has("task_id")) return taskId.toString().equals(event.get("task_id").getAsString());
        // 身体安全和生命周期信号仍会中断任务级等待；聊天消息由 ChatFlow 单独处理。
        return !"background".equals(event.get("priority").getAsString());
    }

    private static boolean background(String type) {
        return type.startsWith("world.");
    }
}
