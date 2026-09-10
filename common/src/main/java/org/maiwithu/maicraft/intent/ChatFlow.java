// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** 记住最近收到的二百五十六条聊天区消息，供主播 Agent 订阅和读取；不进任务 Attention 流。 */
final class ChatFlow {
    private static final int CAPACITY = 256;
    private final List<JsonObject> messages = new ArrayList<>();
    private final CopyOnWriteArrayList<Consumer<JsonElement>> listeners = new CopyOnWriteArrayList<>();
    private String streamId = UUID.randomUUID().toString();
    private long cursor;

    void publish(String type, String message, JsonObject data) {
        // 每条消息给一个递增编号并复制内容；满了就移走最旧消息，最后通知正在等新消息的调用者。
        JsonObject signal;
        synchronized (this) {
            JsonObject entry = new JsonObject();
            entry.addProperty("cursor", ++cursor);
            entry.addProperty("type", type);
            entry.addProperty("timestamp", Instant.now().toString());
            entry.addProperty("message", message == null ? "" : message);
            entry.add("data", data == null ? new JsonObject() : data.deepCopy());
            messages.add(entry);
            if (messages.size() > CAPACITY) messages.removeFirst();
            signal = read(cursor - 1, 1, streamId);
        }
        notifyListeners(signal);
    }

    synchronized JsonObject checkpoint() {
        // stream_id 表示这一轮消息流，cursor 表示读到了哪条；换世界后两者要重新同步。
        JsonObject result = new JsonObject();
        result.addProperty("stream_id", streamId);
        result.addProperty("cursor", cursor);
        return result;
    }

    synchronized JsonObject read(long after, int limit, String expectedStream) {
        // 调用者带来的消息流编号变了，或游标比当前最新消息还大，说明它拿着另一轮的进度，需要重新对齐。
        if (after < 0 || limit < 1) throw new IllegalArgumentException("Invalid chat cursor or limit");
        boolean reset = expectedStream != null && !streamId.equals(expectedStream) || after > cursor;
        boolean initial = expectedStream == null && after == 0;
        long oldest = messages.isEmpty() ? cursor + 1 : messages.getFirst().get("cursor").getAsLong();
        long from = reset ? 0 : after;
        boolean lost = !initial && from < oldest - 1;
        List<JsonObject> matching = messages.stream()
                .filter(entry -> entry.get("cursor").getAsLong() > from).toList();
        // 第一次未带进度时只看最近几条；已经带进度时从那里往后读，不能跳过中间尚未读完的消息。
        int start = initial ? Math.max(0, matching.size() - limit) : 0;
        JsonArray page = new JsonArray();
        int end = Math.min(matching.size(), start + limit);
        for (int i = start; i < end; i++) page.add(matching.get(i).deepCopy());
        boolean more = end < matching.size();
        long next = more ? matching.get(end - 1).get("cursor").getAsLong() : cursor;
        JsonObject result = checkpoint();
        result.addProperty("cursor", next);
        result.addProperty("latest_cursor", cursor);
        result.addProperty("oldest_cursor", oldest);
        result.addProperty("has_more", more);
        result.addProperty("history_lost", lost);
        result.addProperty("stream_reset", reset);
        result.addProperty("resync_required", reset || lost);
        result.add("messages", page);
        return result;
    }

    AutoCloseable subscribe(Consumer<JsonElement> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    void clear() {
        // 换世界等情况下清空旧消息并换一个 stream_id，让等待者知道旧游标不能继续用了。
        JsonObject signal;
        synchronized (this) {
            messages.clear();
            cursor = 0;
            streamId = UUID.randomUUID().toString();
            signal = checkpoint();
            signal.addProperty("stream_reset", true);
        }
        notifyListeners(signal);
    }

    private void notifyListeners(JsonObject signal) {
        // 每个订阅者拿到自己的副本；某个订阅者处理失败，也不影响其他人或聊天继续送达。
        for (Consumer<JsonElement> listener : listeners) {
            try { listener.accept(signal.deepCopy()); }
            catch (RuntimeException ignored) { /* A subscriber cannot break chat delivery. */ }
        }
    }
}
