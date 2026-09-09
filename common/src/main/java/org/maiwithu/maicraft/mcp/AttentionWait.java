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

/** 处理“等有新消息再回复”：用事件通知和超时计时器等待，不阻塞游戏线程，也不接管玩家。 */
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
            // 回复、失败或取消后，都删掉订阅并取消计时器，不能让已结束的等待继续占资源。
            close(subscription.getAndSet(null));
            ScheduledFuture<?> timer = deadline.getAndSet(null);
            if (timer != null) timer.cancel(false);
        });
    }

    static AttentionWait start(Supplier<JsonObject> snapshot,
            Function<Consumer<JsonElement>, AutoCloseable> subscribe, Executor client, int waitMs) {
        AttentionWait wait = new AttentionWait(snapshot, client);
        wait.dispatch(() -> {
            // 先看一次当前状态；任务如果已经结束或正在等人回答，就没必要继续等新事件。
            wait.read(false);
            if (wait.isDone()) return;
            AutoCloseable handle = subscribe.apply(ignored -> wait.dispatch(() -> wait.read(false)));
            wait.subscription.set(handle);
            if (wait.isDone()) { close(wait.subscription.getAndSet(null)); return; }
            // 订阅后再看一次，补上“第一次查看完、还没订阅上”这小段间隙可能发生的消息。
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
        // 有值得回复的变化就立即回复；一直没变化时，到时间返回 timeout，它不表示游戏任务超时。
        if (isDone()) return;
        JsonObject current = snapshot.get();
        if (!"idle".equals(current.get("wake_reason").getAsString())) complete(current);
        else if (timedOut) {
            current.addProperty("wake_reason", "timeout");
            complete(current);
        }
    }

    private void dispatch(Runnable work) {
        // 读取任务和世界状态仍交给游戏线程；消息通知或计时器所在的线程不能直接读取可变游戏对象。
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
        // 取消的是“等回复”这次请求，不是游戏里的任务；玩家正在做的事继续由原任务负责。
        return cancel(false) ? RuntimeFacade.CancellationDisposition.CANCELLED_WHILE_WAITING
                : RuntimeFacade.CancellationDisposition.SETTLED;
    }

    private static void close(AutoCloseable handle) {
        if (handle == null) return;
        try { handle.close(); } catch (Exception ignored) { }
    }
}
