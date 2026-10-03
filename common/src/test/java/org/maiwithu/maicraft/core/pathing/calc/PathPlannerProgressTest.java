package org.maiwithu.maicraft.core.pathing.calc;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 算路总耗时可以超过预算；新计算事实补满无进展时间，诊断轮询不会续期。 */
public final class PathPlannerProgressTest {
    public static void main(String[] args) throws Exception {
        var first = new CountDownLatch(1); var advance = new CountDownLatch(1);
        var second = new CountDownLatch(1); var release = new CountDownLatch(1);
        var work = PathPlannerPool.submit(() -> {
            PathPlannerPool.madeProgress("node_expansion"); first.countDown(); await(advance);
            PathPlannerPool.madeProgress("path_movement_verified"); second.countDown(); await(release);
            return 42;
        });
        try {
            check(first.await(5, TimeUnit.SECONDS), "worker starts and reports completed work");
            long old = System.nanoTime() - TimeUnit.SECONDS.toNanos(60);
            set(work, "submitted", old); set(work, "progressedAt", old);
            check(PathPlannerPool.ageMillis(work) >= 59000 && PathPlannerPool.noProgressMillis(work) >= 59000,
                    "unproductive work consumes its budget");
            advance.countDown(); check(second.await(5, TimeUnit.SECONDS), "the next real stage finishes");
            check(PathPlannerPool.ageMillis(work) >= 59000 && PathPlannerPool.noProgressMillis(work) < 5000,
                    "progress refills the idle budget without rewriting total task age");
            check(PathPlannerPool.completedUnits(work) == 2, "only completed units count");
            set(work, "progressedAt", old);
            for (int i = 0; i < 5; i++) PathPlannerPool.describe(work, false);
            check(PathPlannerPool.noProgressMillis(work) >= 59000 && PathPlannerPool.completedUnits(work) == 2,
                    "polling and a live worker cannot impersonate progress");
            check(PathPlannerPool.describe(work, false).get("progress_stage").equals("path_movement_verified"),
                    "diagnostics identify the last completed stage");
        } finally { advance.countDown(); release.countDown(); }
        check(work.get(5, TimeUnit.SECONDS) == 42, "normal completion remains intact");
        System.out.println("PathPlannerProgressTest: passed");
    }
    private static void set(Object work, String name, long value) throws Exception {
        Field field = work.getClass().getDeclaredField(name); field.setAccessible(true); field.setLong(work, value);
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("progress fixture did not resume"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
