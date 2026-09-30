// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.time.Instant;

/** 记住最近收到的二百五十六条聊天区消息，供主播 Agent 订阅和读取；不进任务 Attention 流。游标、分页与订阅机制见 {@link BoundedMessageStream}。 */
final class ChatFlow extends BoundedMessageStream {
    @Override protected String pageKey() { return "messages"; }
    @Override protected String label() { return "chat"; }

    void publish(String type, String message, JsonObject data) {
        // 每条消息给一个递增编号并复制内容；满了就移走最旧消息，最后通知正在等新消息的调用者。
        JsonObject signal;
        synchronized (this) {
            JsonObject entry = new JsonObject();
            entry.addProperty("cursor", nextCursor());
            entry.addProperty("type", type);
            entry.addProperty("timestamp", Instant.now().toString());
            entry.addProperty("message", message == null ? "" : message);
            entry.add("data", data == null ? new JsonObject() : data.deepCopy());
            store(entry);
            signal = readPage(currentCursor() - 1, 1, streamId(), event -> true);
        }
        notifyListeners(signal);
    }

    synchronized JsonObject read(long after, int limit, String expectedStream) {
        return readPage(after, limit, expectedStream, event -> true);
    }
}
