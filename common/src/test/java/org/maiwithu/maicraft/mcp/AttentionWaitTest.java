// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class AttentionWaitTest {
    public static void main(String[] args) throws Exception {
        Fixture f = new Fixture();
        f.reason = "task_terminal";
        var done = f.start(60_000);
        f.tick();
        check(done.isDone() && f.listeners.get() == 0, "already finished task never waits");
        f = new Fixture();
        f.duringSubscribe = () -> { };
        Fixture racing = f;
        f.duringSubscribe = () -> racing.reason = "decision_required";
        var race = f.start(60_000);
        f.tick();
        check(race.isDone() && f.listeners.get() == 0, "second read closes registration race and detaches listener");
        f = new Fixture();
        var waiting = f.start(60_000);
        f.tick();
        check(!waiting.isDone() && f.listeners.get() == 1, "idle waits without a polling loop");
        f.listener.accept(new JsonObject());
        f.tick();
        check(!waiting.isDone(), "unrelated event does not finish task wait");
        f.reason = "task_terminal";
        f.listener.accept(new JsonObject());
        f.tick();
        check(waiting.isDone() && f.listeners.get() == 0, "completion event wakes and cleans up");
        f = new Fixture();
        var timeout = f.start(5);
        f.tick();
        f.cursor = 99;
        f.tick();
        JsonObject result = timeout.get(1, TimeUnit.SECONDS).getAsJsonObject();
        check(result.get("cursor").getAsInt() == 99 && result.get("wake_reason").getAsString().equals("timeout"),
                "timeout reads current cursor on client thread, not a stale setup snapshot");
        f = new Fixture();
        var cancelled = f.start(60_000);
        cancelled.cancelCall(); f.tick();
        check(f.reads == 0 && f.listeners.get() == 0, "cancel before dispatch does no reads or subscriptions");
        var active = f.start(60_000); f.tick();
        active.cancelCall();
        check(f.listeners.get() == 0, "cancel while waiting detaches listener");
        f.listener.accept(new JsonObject());
        check(f.queue.isEmpty(), "late event after cancellation queues no work");
        f = new Fixture();
        var failed = f.start(60_000); f.tick();
        f.fail = true; f.listener.accept(new JsonObject()); f.tick();
        check(failed.isCompletedExceptionally() && f.listeners.get() == 0, "read failure cleans subscription");
        System.out.println("AttentionWaitTest: passed");
    }

    private static final class Fixture {
        final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
        final AtomicInteger listeners = new AtomicInteger();
        final Thread owner = Thread.currentThread();
        Consumer<JsonElement> listener;
        Runnable duringSubscribe = () -> { };
        String reason = "idle";
        int reads, cursor;
        boolean fail;

        AttentionWait start(int timeout) {
            return AttentionWait.start(() -> {
                check(Thread.currentThread() == owner, "never read game state on timer thread");
                if (fail) throw new IllegalStateException("world unavailable");
                reads++;
                JsonObject result = new JsonObject();
                result.addProperty("wake_reason", reason); result.addProperty("cursor", cursor);
                return result;
            }, sink -> {
                listener = sink; listeners.incrementAndGet(); duringSubscribe.run();
                return listeners::decrementAndGet;
            }, queue::add, timeout);
        }

        void tick() throws Exception {
            Runnable work = queue.poll(2, TimeUnit.SECONDS);
            check(work != null, "expected client callback"); work.run();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
