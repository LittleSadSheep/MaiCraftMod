// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChatFlowTest {
    public static void main(String[] args) throws Exception {
        ChatFlow flow = new ChatFlow();
        String stream = flow.checkpoint().get("stream_id").getAsString();
        for (int i = 0; i < 25; i++) flow.publish("player.chat_received", "hello " + i, null);
        JsonObject first = flow.read(0, 1, null).getAsJsonArray("messages").get(0).getAsJsonObject();
        check(first.has("type") && first.has("timestamp") && first.has("message") && first.has("data"),
                "chat entries carry type, timestamp, message and data");
        check(!first.has("priority") && !first.has("task_id"),
                "chat entries carry no attention priority or task");
        long cursor = 0;
        int received = 0;
        JsonObject page;
        do {
            page = flow.read(cursor, 10, stream);
            for (var message : page.getAsJsonArray("messages")) {
                check(message.getAsJsonObject().get("cursor").getAsLong() == ++received,
                        "no skipped or repeated messages");
            }
            cursor = page.get("cursor").getAsLong();
        } while (page.get("has_more").getAsBoolean());
        check(received == 25 && cursor == 25, "drain every page including the last messages");
        check(flow.read(0, 10, null).getAsJsonArray("messages").size() == 10,
                "initial read returns only the recent window");
        AtomicInteger signals = new AtomicInteger();
        var bad = flow.subscribe(ignored -> { throw new IllegalStateException("disconnected reader"); });
        var good = flow.subscribe(signal -> { signals.incrementAndGet(); signal.getAsJsonObject().addProperty("cursor", -1); });
        flow.publish("game.message_received", "system", null);
        check(signals.get() == 1 && flow.checkpoint().get("cursor").getAsLong() > 0, "subscriber isolation");
        good.close(); good.close(); bad.close();
        for (int i = 0; i < 300; i++) flow.publish("player.chat_received", "noise", null);
        page = flow.read(1, 20, stream);
        check(page.get("history_lost").getAsBoolean() && page.get("resync_required").getAsBoolean(),
                "overflow cannot look like a reliable empty flow");
        check(signals.get() == 1, "unsubscribe is idempotent");
        flow.clear();
        page = flow.read(cursor, 10, stream);
        check(page.get("stream_reset").getAsBoolean() && page.get("cursor").getAsLong() == 0,
                "world reset invalidates old checkpoints");
        page = new ChatFlow().read(0, 10, stream);
        check(page.get("stream_reset").getAsBoolean(), "restart detected even if the numeric cursor is zero");
        page = flow.read(999, 10, null);
        check(page.get("stream_reset").getAsBoolean(), "legacy cursor ahead of stream must not wait forever");
        System.out.println("ChatFlowTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
