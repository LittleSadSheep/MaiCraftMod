// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.mcp.tool.ClientThread;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * 客户端刻里的工作队列：MCP 请求线程把要读写游戏状态的工作排进来，客户端每刻结束时按排队顺序做完，
 * 结果交回等着的请求线程。这样下达目标、暂停、回答都和控制循环推进同一个目标的代码在同一个线程上，
 * 不会互相踩到。角色不在世界里时，排着的工作都以"不在世界里"结束。
 */
final class ClientTickWork implements ClientThread {
    /** 请求线程最多等几秒；正常情况下一刻（50 毫秒）之内就轮到。 */
    static final long WAIT_SECONDS = 5;

    private final ConcurrentLinkedQueue<Job<?>> queue = new ConcurrentLinkedQueue<>();

    @Override public <T> T call(Function<TickContext, T> work) {
        Job<T> job = new Job<>(work, new CompletableFuture<>());
        queue.add(job);
        try {
            return job.result().get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            // 超时后不再做这件工作：请求方已经得到"忙"的答复，晚到的执行只会造成没人知道的变化。
            job.result().cancel(false);
            throw new Busy("客户端 " + WAIT_SECONDS + " 秒内没有轮到这次请求，稍后再试");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(failed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            job.result().cancel(false);
            throw new Busy("等客户端时被中断");
        }
    }

    /**
     * 每个客户端刻调用一次，做完此刻排着的全部工作。
     *
     * @param context 本刻的上下文；角色不在世界里时为 null
     */
    void drain(TickContext context) {
        Job<?> job;
        while ((job = queue.poll()) != null) {
            if (context == null) {
                job.result().completeExceptionally(new NotInWorld());
            } else {
                job.run(context);
            }
        }
    }

    /** 一件排着的工作和等它结果的人。 */
    private record Job<T>(Function<TickContext, T> work, CompletableFuture<T> result) {
        void run(TickContext context) {
            if (result.isDone()) return;
            try {
                result.complete(work.apply(context));
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        }
    }
}
