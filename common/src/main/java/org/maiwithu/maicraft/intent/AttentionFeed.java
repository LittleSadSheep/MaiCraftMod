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

/** Bounded event history, not a task store. Checkpoints identify both the stream and read position. */
final class AttentionFeed {
    private static final int CAPACITY = 256;
    private final List<JsonObject> events = new ArrayList<>();
    private final CopyOnWriteArrayList<Consumer<JsonElement>> listeners = new CopyOnWriteArrayList<>();
    private String streamId = UUID.randomUUID().toString();
    private long cursor;

    void publish(String type, UUID taskId, String message, JsonObject data) {
        JsonObject signal;
        synchronized (this) {
            JsonObject event = new JsonObject();
            event.addProperty("cursor", ++cursor);
            event.addProperty("type", type);
            event.addProperty("timestamp", Instant.now().toString());
            if (taskId != null) event.addProperty("task_id", taskId.toString());
            event.addProperty("priority", taskId != null ? "task" : background(type) ? "background" : "important");
            event.addProperty("message", message == null ? "" : message);
            event.add("data", data == null ? new JsonObject() : data.deepCopy());
            events.add(event);
            if (events.size() > CAPACITY) events.removeFirst();
            signal = read(cursor - 1, 1, streamId, null);
        }
        notifyListeners(signal);
    }

    synchronized JsonObject checkpoint() {
        JsonObject result = new JsonObject();
        result.addProperty("stream_id", streamId);
        result.addProperty("cursor", cursor);
        return result;
    }

    synchronized JsonObject read(long after, int limit, String expectedStream, UUID taskId) {
        if (after < 0 || limit < 1) throw new IllegalArgumentException("Invalid attention cursor or limit");
        boolean reset = expectedStream != null && !streamId.equals(expectedStream) || after > cursor;
        boolean initial = expectedStream == null && after == 0;
        long oldest = events.isEmpty() ? cursor + 1 : events.getFirst().get("cursor").getAsLong();
        long from = reset ? 0 : after;
        boolean lost = !initial && from < oldest - 1;
        List<JsonObject> matching = events.stream()
                .filter(event -> event.get("cursor").getAsLong() > from)
                .filter(event -> matches(event, taskId)).toList();
        // A cursor-less read is a recent snapshot. Explicit checkpoints always page forward.
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
        result.add("events", page);
        return result;
    }

    private static boolean matches(JsonObject event, UUID taskId) {
        if (taskId == null) return true;
        if (event.has("task_id")) return taskId.toString().equals(event.get("task_id").getAsString());
        // Body safety, player interaction and lifecycle signals still interrupt a task-scoped wait.
        return !"background".equals(event.get("priority").getAsString());
    }

    private static boolean background(String type) {
        return type.startsWith("world.") || "game.message_received".equals(type);
    }

    AutoCloseable subscribe(Consumer<JsonElement> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    void clear() {
        JsonObject signal;
        synchronized (this) {
            events.clear();
            cursor = 0;
            streamId = UUID.randomUUID().toString();
            signal = checkpoint();
            signal.addProperty("stream_reset", true);
        }
        notifyListeners(signal);
    }

    private void notifyListeners(JsonObject signal) {
        for (Consumer<JsonElement> listener : listeners) {
            try { listener.accept(signal.deepCopy()); }
            catch (RuntimeException ignored) { /* A subscriber cannot break task settlement. */ }
        }
    }
}
