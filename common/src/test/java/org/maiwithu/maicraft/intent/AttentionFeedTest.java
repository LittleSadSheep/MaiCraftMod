// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class AttentionFeedTest {
    public static void main(String[] args) throws Exception {
        AttentionFeed feed = new AttentionFeed();
        String stream = feed.checkpoint().get("stream_id").getAsString();
        UUID task = UUID.randomUUID();
        for (int i = 0; i < 25; i++) feed.publish("completed", task, "done", null);
        long cursor = 0;
        int received = 0;
        JsonObject page;
        do {
            page = feed.read(cursor, 10, stream, task);
            for (var event : page.getAsJsonArray("events")) {
                check(event.getAsJsonObject().get("cursor").getAsLong() == ++received, "no skipped or repeated events");
            }
            cursor = page.get("cursor").getAsLong();
        } while (page.get("has_more").getAsBoolean());
        check(received == 25 && cursor == 25, "drain every page including the last five completions");
        feed.publish("world.time_phase_changed", null, "night", null);
        feed.publish("world.weather_changed", null, "rain", null);
        feed.publish("completed", UUID.randomUUID(), "other task", null);
        check(feed.read(cursor, 10, stream, task).getAsJsonArray("events").isEmpty(), "task wait suppresses unrelated noise");
        feed.publish("agent.damaged", null, "damage", null);
        feed.publish("agent.reflex", null, "reflex", null);
        check(feed.read(cursor, 10, stream, task).getAsJsonArray("events").size() == 2, "safety and body events remain visible");
        check(feed.read(cursor, 10, stream, null).getAsJsonArray("events").size() == 5, "global readers retain all events");
        AtomicInteger signals = new AtomicInteger();
        var bad = feed.subscribe(ignored -> { throw new IllegalStateException("disconnected reader"); });
        var good = feed.subscribe(signal -> { signals.incrementAndGet(); signal.getAsJsonObject().addProperty("cursor", -1); });
        feed.publish("decision", task, "choose", null);
        check(signals.get() == 1 && feed.checkpoint().get("cursor").getAsLong() > 0, "subscriber isolation");
        good.close(); good.close(); bad.close();
        for (int i = 0; i < 300; i++) feed.publish("world.weather_changed", null, "noise", null);
        page = feed.read(1, 20, stream, task);
        check(page.get("history_lost").getAsBoolean() && page.get("resync_required").getAsBoolean(), "overflow cannot look like a reliable empty feed");
        check(signals.get() == 1, "unsubscribe is idempotent");
        feed.clear();
        page = feed.read(cursor, 10, stream, task);
        check(page.get("stream_reset").getAsBoolean() && page.get("cursor").getAsLong() == 0, "world reset invalidates old checkpoints");
        page = new AttentionFeed().read(0, 10, stream, task);
        check(page.get("stream_reset").getAsBoolean(), "restart detected even if the numeric cursor is zero");
        page = feed.read(999, 10, null, task);
        check(page.get("stream_reset").getAsBoolean(), "legacy cursor ahead of stream must not wait forever");
        System.out.println("AttentionFeedTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
