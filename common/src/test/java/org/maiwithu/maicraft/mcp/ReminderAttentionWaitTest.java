// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.maiwithu.maicraft.intent.ReminderBoard;

/** 真实长轮询只被合并后的通知唤醒；临时解除、文案回摆和读取快照都不能让等待提前结束。 */
public final class ReminderAttentionWaitTest {
    public static void main(String[] args) throws Exception {
        var listeners = new CopyOnWriteArrayList<Consumer<JsonElement>>();
        var cursor = new AtomicInteger();
        var clock = new AtomicLong();
        var board = new ReminderBoard(event -> {
            cursor.incrementAndGet(); listeners.forEach(listener -> listener.accept(event));
        }, clock::get);
        board.update("gear", "装备将坏", new JsonObject(), new JsonArray(), 0);
        int checkpoint = cursor.get();
        Supplier<JsonObject> snapshot = () -> {
            var result = new JsonObject();
            result.addProperty("wake_reason", cursor.get() > checkpoint ? "events" : "idle");
            result.add("reminders", board.snapshot());
            return result;
        };
        var wait = AttentionWait.start(snapshot, listener -> {
            listeners.add(listener); return () -> listeners.remove(listener);
        }, Runnable::run, 30_000);
        try {
            for (int tick = 1; tick <= 400; tick++) {
                clock.set(tick * 50_000_000L);
                if (tick % 2 == 0) board.update("gear", "装备将坏", new JsonObject(), new JsonArray(), tick);
                else board.remove("gear", "临时切换主手", tick);
                board.flush();
            }
            check(!wait.isDone() && board.snapshot().size() == 1, "反复切换不会提前唤醒等待，当前主手提醒仍可查询");
            clock.set(TimeUnit.SECONDS.toNanos(21)); board.remove("gear", "确实换上新工具", 420);
            check(board.snapshot().isEmpty() && !wait.isDone(), "解除先反映到快照，原等待继续保持");
            clock.set(TimeUnit.SECONDS.toNanos(30)); board.flush();
            JsonObject result = wait.get(1, TimeUnit.SECONDS).getAsJsonObject();
            check(result.get("wake_reason").getAsString().equals("events") && result.getAsJsonArray("reminders").isEmpty()
                    && cursor.get() == 2, "冷却和稳定时间满足后，仅一次解除通知唤醒并返回当前快照");
        } finally { wait.cancel(false); }
        // 一个 ID 仍在冷却，另一个从未见过的危险也必须立即唤醒，不能做成整个提醒系统的全局静音。
        int afterClear = cursor.get();
        var next = AttentionWait.start(() -> {
            var result = new JsonObject(); result.addProperty("wake_reason", cursor.get() > afterClear ? "events" : "idle");
            result.add("reminders", board.snapshot()); return result;
        }, listener -> { listeners.add(listener); return () -> listeners.remove(listener); }, Runnable::run, 30_000);
        try {
            clock.set(TimeUnit.SECONDS.toNanos(31)); board.update("gear", "装备将坏", new JsonObject(), new JsonArray(), 620);
            board.flush(); check(!next.isDone(), "旧风险重现仍保留通知冷却");
            board.update("drowning", "水下缺氧", new JsonObject(), new JsonArray(), 620);
            check(next.get(1, TimeUnit.SECONDS).getAsJsonObject().getAsJsonArray("reminders").size() == 2,
                    "首次新危险立即唤醒，回执同时保留冷却中的另一条当前提醒");
        } finally { next.cancel(false); }
        System.out.println("ReminderAttentionWaitTest: passed");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
