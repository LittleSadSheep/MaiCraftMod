package org.maiwithu.maicraft.core.pathing.calc;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 模拟旧会话的区块读取不响应中断，验证新路线能重新计算且隔离次数不会无限增开工作线程。 */
public final class PathPlannerRecoveryTest {
    public static void main(String[] args) throws Exception {
        int size = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 2));
        var started = new CountDownLatch(size); var release = new CountDownLatch(1);
        var old = new ArrayList<CompletableFuture<Integer>>();
        try {
            for (int i = 0; i < size; i++) old.add(PathPlannerPool.submit(() -> {
                started.countDown(); awaitUninterruptibly(release); return -1;
            }));
            check(started.await(5, TimeUnit.SECONDS), "old searches occupy every worker");
            // 旧导航反复被撤销也不积压幽灵队列；请求仍未执行时就可准确报告 queued。
            for (int i = 0; i < 40; i++) {
                var cancelled = PathPlannerPool.submit(() -> 0);
                check(PathPlannerPool.describe(cancelled, false).get("phase").equals("queued"), "queued search is distinguishable from a running search");
                cancelled.cancel(false);
            }
            var waiting = PathPlannerPool.submit(() -> 9);
            check(PathPlannerPool.describe(waiting, false).get("queued_searches").equals(1), "cancelled work no longer exhausts queue capacity");
            check(PathPlannerPool.recover(waiting), "stalled generation can be retired once");
            check(waiting.isCancelled() && old.stream().allMatch(CompletableFuture::isCancelled), "all retired futures settle without publishing stale paths");
            check(PathPlannerPool.submit(() -> 42).get(5, TimeUnit.SECONDS) == 42, "new route calculates while old readers remain stuck");
            var newStarted = new CountDownLatch(1);
            var blockedAgain = PathPlannerPool.submit(() -> { newStarted.countDown(); awaitUninterruptibly(release); return 3; });
            check(newStarted.await(5, TimeUnit.SECONDS), "replacement worker starts");
            check(!PathPlannerPool.recover(blockedAgain), "another live retired generation prevents unbounded thread replacement");
            check(PathPlannerPool.activeThreads() >= size + 1 && PathPlannerPool.liveThreads() <= size * 2,
                    "telemetry includes quarantined workers without exceeding two generations");
            check(PathPlannerPool.describe(blockedAgain, true).containsKey("worker_stack"), "failed recovery exposes the blocked worker stack");
            release.countDown(); check(blockedAgain.get(5, TimeUnit.SECONDS) == 3, "live work can still finish after refused recovery");
        } finally { release.countDown(); }
        System.out.println("PathPlannerRecoveryTest: bounded isolation and queued cancellation passed");
    }
    private static void awaitUninterruptibly(CountDownLatch release) {
        while (release.getCount() != 0) try { release.await(); } catch (InterruptedException ignored) { }
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
