// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** One cancellable subscription. All snapshots, including the deadline read, run on the client executor. */
final class AttentionWait extends CompletableFuture<JsonElement> implements RuntimeFacade.ManagedCall {
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(
            work -> Thread.ofPlatform().daemon().name("maicraft-attention-deadline").unstarted(work));
    private final Supplier<JsonObject> snapshot;
    private final Executor client;
    private final AtomicReference<AutoCloseable> subscription = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> deadline = new AtomicReference<>();

    private AttentionWait(Supplier<JsonObject> snapshot, Executor client) {
        this.snapshot = snapshot;
        this.client = client;
        whenComplete((ignored, failure) -> {
            close(subscription.getAndSet(null));
            ScheduledFuture<?> timer = deadline.getAndSet(null);
            if (timer != null) timer.cancel(false);
        });
    }

    static AttentionWait start(Supplier<JsonObject> snapshot,
            Function<Consumer<JsonElement>, AutoCloseable> subscribe, Executor client, int waitMs) {
        AttentionWait wait = new AttentionWait(snapshot, client);
        wait.dispatch(() -> {
            wait.read(false);
            if (wait.isDone()) return;
            AutoCloseable handle = subscribe.apply(ignored -> wait.dispatch(() -> wait.read(false)));
            wait.subscription.set(handle);
            if (wait.isDone()) { close(wait.subscription.getAndSet(null)); return; }
            // Catch an event between the initial read and listener registration.
            wait.read(false);
            if (wait.isDone()) return;
            ScheduledFuture<?> timer = DEADLINES.schedule(
                    () -> wait.dispatch(() -> wait.read(true)), waitMs, TimeUnit.MILLISECONDS);
            wait.deadline.set(timer);
            if (wait.isDone()) {
                timer = wait.deadline.getAndSet(null);
                if (timer != null) timer.cancel(false);
            }
        });
        return wait;
    }

    private void read(boolean timedOut) {
        if (isDone()) return;
        JsonObject current = snapshot.get();
        if (!"idle".equals(current.get("wake_reason").getAsString())) complete(current);
        else if (timedOut) {
            current.addProperty("wake_reason", "timeout");
            complete(current);
        }
    }

    private void dispatch(Runnable work) {
        if (isDone()) return;
        try {
            client.execute(() -> {
                if (isDone()) return;
                try { work.run(); }
                catch (Throwable failure) { completeExceptionally(failure); }
            });
        } catch (Throwable failure) { completeExceptionally(failure); }
    }

    @Override public RuntimeFacade.CancellationDisposition cancelCall() {
        // Only the read wait is cancelled. This operation never owns or cancels the game task.
        return cancel(false) ? RuntimeFacade.CancellationDisposition.CANCELLED_WHILE_WAITING
                : RuntimeFacade.CancellationDisposition.SETTLED;
    }

    private static void close(AutoCloseable handle) {
        if (handle == null) return;
        try { handle.close(); } catch (Exception ignored) { }
    }
}
