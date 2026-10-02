package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.calc.AbstractNodeCostSearch;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.maiwithu.maicraft.core.pathing.calc.PathPlannerPool;
import sun.misc.Unsafe;

/** 不改世界的地形建议也要结清实际搜索：验证超时可见，取消同时停止 A* 与工作请求。 */
public final class TerrainProbeCancellationTest {
    public static void main(String[] args) throws Exception {
        var memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        var search = (AStarPathFinder) memory.allocateInstance(AStarPathFinder.class);
        var constructor = EmbeddedBaritoneTerrainProbe.ProbeFuture.class.getDeclaredConstructor(AbstractNodeCostSearch.class, long.class);
        constructor.setAccessible(true);
        var probe = constructor.newInstance(search, -1L);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var work = PathPlannerPool.submit(() -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
            return null;
        });
        field(probe.getClass(), "calculation").set(probe, work);
        try {
            check(started.await(5, TimeUnit.SECONDS), "probe worker started");
            check(probe.stalled() && probe.diagnostics(true).containsKey("pool_worker_stacks"), "probe reports its own timed-out worker evidence");
            probe.cancel(true);
            check(probe.isCancelled() && work.isCancelled() && field(AbstractNodeCostSearch.class, "cancelRequested").getBoolean(search),
                    "cancelling the report also retires the real queued/running search and cooperative A* state");
        } finally { release.countDown(); }
        System.out.println("TerrainProbeCancellationTest: bounded probe wait and cancellation passed");
    }
    private static Field field(Class<?> type, String name) throws Exception { var f = type.getDeclaredField(name); f.setAccessible(true); return f; }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
