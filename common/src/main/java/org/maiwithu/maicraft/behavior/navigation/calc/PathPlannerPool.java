package org.maiwithu.maicraft.behavior.navigation.calc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.function.Supplier;

/**
 * 给现用 Baritone 寻路和地形检查分配后台计算线程：最多两个线程，另有三十二个排队位置。
 * 队列满了返回失败，不让这次计算改在游戏线程上硬跑；空闲线程一分钟后可退出。提交者负责准备可在后台使用的数据。
 */
public final class PathPlannerPool {

    private PathPlannerPool() {}

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final AtomicInteger PEAK = new AtomicInteger();
    private static final AtomicLong COMPLETED = new AtomicLong();
    private static final ThreadLocal<Work<?>> ACTIVE_WORK = new ThreadLocal<>();

    /** 通常只运行一个搜索；第二个 worker 仅用于处理取消过程中的短暂重叠。 */
    private static final int POOL_SIZE = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 2));

    private static volatile Generation current = new Generation(0);
    private static Generation retired;

    /** 一代只持有自己的搜索与线程；旧搜索不响应取消时最多隔离一代，防止反复重试无限增开线程。 */
    private static final class Generation {
        final int number;
        final ThreadPoolExecutor executor = createPool();
        final Set<Work<?>> jobs = ConcurrentHashMap.newKeySet();
        Generation(int number) { this.number = number; }
    }

    private static final class Work<T> extends CompletableFuture<T> implements Runnable {
        final Generation generation;
        final Supplier<T> task;
        final long submitted = System.nanoTime();
        volatile Thread worker;
        volatile long started;
        volatile long progressedAt;
        volatile long completedUnits;
        volatile String progressStage = "queued";
        Work(Generation generation, Supplier<T> task) { this.generation = generation; this.task = task; }
        @Override public void run() {
            synchronized (this) {
                if (isDone()) { generation.jobs.remove(this); return; }
                worker = Thread.currentThread(); started = System.nanoTime();
                progressedAt = started; progressStage = "started";
            }
            PEAK.accumulateAndGet(liveThreads(), Math::max);
            ACTIVE_WORK.set(this);
            try { complete(task.get()); }
            catch (Throwable failure) { completeExceptionally(failure); }
            finally { ACTIVE_WORK.remove(); synchronized (this) { worker = null; generation.jobs.remove(this); COMPLETED.incrementAndGet(); } }
        }
        @Override public synchronized boolean cancel(boolean interrupt) {
            boolean changed = super.cancel(interrupt);
            if (!changed) return false;
            // 撤销旧导航时同时撤下队列中的计算，不能让被丢弃的请求继续占满新任务的排队位置。
            generation.executor.remove(this);
            if (worker == null) generation.jobs.remove(this);
            else if (interrupt) worker.interrupt();
            return true;
        }
    }

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

    /** 在规划线程池中执行 {@code task}，并将结果写入返回的 future。 */
    // 把计算排到后台并立即返回一个等待结果的对象；如果连队列也进不去，返回的对象直接带失败原因。
    public static synchronized <T> CompletableFuture<T> submit(Supplier<T> task) {
        var work = new Work<>(current, task); current.jobs.add(work);
        try {
            current.executor.execute(work);
        } catch (RejectedExecutionException rejected) {
            // 绝不能回退到 CallerRunsPolicy，因为提交者就是 Minecraft 客户端线程。
            current.jobs.remove(work); work.completeExceptionally(rejected);
        }
        return work;
    }

    /** 客户端只读当前计算的排队与运行事实，超时才额外获取线程栈；不读取工作线程可变的路径节点。 */
    public static Map<String, Object> describe(CompletableFuture<?> future, boolean includeStack) {
        if (!(future instanceof Work<?> work)) return Map.of("phase", future == null ? "idle" : future.isDone() ? "completed" : "untracked");
        Thread worker = work.worker;
        var result = new LinkedHashMap<String, Object>();
        result.put("phase", work.isDone() ? "completed" : worker == null ? "queued" : "running");
        result.put("age_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - work.submitted));
        result.put("no_progress_ms", noProgressMillis(work));
        result.put("completed_work_units", work.completedUnits);
        result.put("progress_stage", work.progressStage);
        result.put("generation", work.generation.number);
        result.put("active_workers", work.generation.executor.getActiveCount());
        result.put("queued_searches", work.generation.executor.getQueue().size());
        if (includeStack) {
            // 排队请求自身没有线程栈，仍须呈现占住这一代工作线程的其他搜索，才能定位真正的阻塞组件。
            var stacks = new LinkedHashMap<String, List<String>>();
            for (var job : work.generation.jobs) {
                Thread active = job.worker;
                if (active != null) stacks.put(active.getName(), Arrays.stream(active.getStackTrace()).map(StackTraceElement::toString).toList());
            }
            result.put("pool_worker_stacks", stacks);
        }
        if (worker != null) {
            result.put("worker", worker.getName()); result.put("worker_state", worker.getState().name());
            if (includeStack) result.put("worker_stack", Arrays.stream(worker.getStackTrace()).map(StackTraceElement::toString).toList());
        }
        return Map.copyOf(result);
    }

    public static long ageMillis(CompletableFuture<?> future) {
        return future instanceof Work<?> work ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - work.submitted) : 0;
    }

    /** 完成节点展开或路径核查后才打点；线程仍活着、重复轮询与请求年龄都不能代替计算进展。 */
    public static void madeProgress(String stage) {
        Work<?> work = ACTIVE_WORK.get();
        if (work == null || work.isDone()) return;
        work.progressStage = stage;
        work.progressedAt = System.nanoTime();
        work.completedUnits++;
    }

    public static long completedUnits(CompletableFuture<?> future) {
        return future instanceof Work<?> work ? work.completedUnits : 0;
    }

    /** 未开始的计算按排队停滞计时；开始后每个已完成工作单元都让无进展预算重新回满。 */
    public static long noProgressMillis(CompletableFuture<?> future) {
        if (!(future instanceof Work<?> work)) return 0;
        long since = work.progressedAt == 0 ? work.submitted : work.progressedAt;
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since));
    }

    /** 只有已确认超时的本代请求可触发隔离；丢弃旧结果并给原任务一个独立计算通道，不冒充已经算出路线。 */
    public static boolean recover(CompletableFuture<?> stalled) {
        Generation previous;
        synchronized (PathPlannerPool.class) {
            if (!(stalled instanceof Work<?> work) || work.generation != current || work.isDone()) return false;
            if (retired != null && !retired.executor.isTerminated()) return false;
            previous = current; retired = previous; current = new Generation(previous.number + 1);
        }
        // 取消会唤醒 future 的回调；在全局锁外结算，避免回调提交新搜索时与旧工作的锁互相等待。
        for (var job : List.copyOf(previous.jobs)) job.cancel(true);
        previous.executor.shutdownNow();
        return true;
    }

    // ==================== 性能探针使用的线程池快照（见 NavProfiler）====================

    /** 包括隔离代的实际存活线程；最多两代，已隔离线程未退出时拒绝再次扩容。 */
    public static synchronized int liveThreads() {
        return current.executor.getPoolSize() + (retired == null ? 0 : retired.executor.getPoolSize());
    }

    /** 当前正在跑搜索的线程数。 */
    public static synchronized int activeThreads() {
        return current.executor.getActiveCount() + (retired == null ? 0 : retired.executor.getActiveCount());
    }

    /** 跨计算代保留线程峰值，恢复不能把已发生的线程占用洗掉。 */
    public static int peakThreads() {
        return PEAK.get();
    }

    /** 累计完成的搜索任务数(取窗口差值即本窗口吞吐)。 */
    public static long completedTasks() {
        return COMPLETED.get();
    }
}
