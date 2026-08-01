package org.maiwithu.maicraft.core.pathing.calc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The shared worker pool that runs A* searches off the Minecraft client thread. Each
 * {@link org.maiwithu.maicraft.core.pathing.execute.PlayerNav} submits its search here and polls the returned
 * future each tick.
 *
 * <h2>Sizing: bounded, CPU-friendly</h2>
 * A* is pure CPU work. MaiCraft controls one local body, so more than two workers only adds heat and
 * contention. The pool is fixed at at most two daemon workers; excess generations queue instead of
 * spawning threads. This leaves the client/render threads responsive on high-core-count machines.
 *
 * <p>Workers are <b>daemon</b> (never hold up JVM shutdown) and run at {@link Thread#MIN_PRIORITY} so
 * the OS scheduler prefers the latency-sensitive game / render threads whenever both are runnable —
 * a cheap best-effort assist on top of the hard thread cap. Idle core threads time out
 * ({@link ThreadPoolExecutor#allowCoreThreadTimeOut}) so the pool holds no threads when nothing navigates.
 */
public final class PathPlannerPool {

    private PathPlannerPool() {}

    private static final AtomicInteger COUNTER = new AtomicInteger();

    /** One active search is normal; a second worker only covers brief cancellation overlap. */
    private static final int POOL_SIZE = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 2));

    private static final ThreadPoolExecutor POOL = createPool();

    private static ThreadPoolExecutor createPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                POOL_SIZE, POOL_SIZE, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "maicraft-path-" + COUNTER.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** Run {@code task} on the planner pool; the result lands in the returned future. */
    public static <T> CompletableFuture<T> submit(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, POOL);
    }

    // ==================== 性能探针用的池快照(见 NavProfiler)====================

    /** 当前存活线程数(有界:上限 {@link #POOL_SIZE};空闲会超时回收)。 */
    public static int liveThreads() {
        return POOL.getPoolSize();
    }

    /** 当前正在跑搜索的线程数。 */
    public static int activeThreads() {
        return POOL.getActiveCount();
    }

    /** 历史峰值线程数(JDK 不可复位,反映最坏时刻的线程膨胀)。 */
    public static int peakThreads() {
        return POOL.getLargestPoolSize();
    }

    /** 累计完成的搜索任务数(取窗口差值即本窗口吞吐)。 */
    public static long completedTasks() {
        return POOL.getCompletedTaskCount();
    }
}
