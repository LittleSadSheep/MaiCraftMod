package org.maiwithu.maicraft.core.pathing.calc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 给现用 Baritone 寻路和地形检查分配后台计算线程：最多两个线程，另有三十二个排队位置。
 * 队列满了返回失败，不让这次计算改在游戏线程上硬跑；空闲线程一分钟后可退出。提交者负责准备可在后台使用的数据。
 */
public final class PathPlannerPool {

    private PathPlannerPool() {}

    private static final AtomicInteger COUNTER = new AtomicInteger();

    /** One active search is normal; a second worker only covers brief cancellation overlap. */
    private static final int POOL_SIZE = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 2));

    private static final ThreadPoolExecutor POOL = createPool();

    private static ThreadPoolExecutor createPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                POOL_SIZE, POOL_SIZE, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(32),
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
    // 把计算排到后台并立即返回一个等待结果的对象；如果连队列也进不去，返回的对象直接带失败原因。
    public static <T> CompletableFuture<T> submit(Supplier<T> task) {
        try {
            return CompletableFuture.supplyAsync(task, POOL);
        } catch (RejectedExecutionException rejected) {
            // Never fall back to CallerRunsPolicy: the submitter is the Minecraft client thread.
            return CompletableFuture.failedFuture(rejected);
        }
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
