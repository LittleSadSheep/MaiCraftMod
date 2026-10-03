package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.PathEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.core.pathing.calc.PathPlannerPool;
import sun.misc.Unsafe;

/** 通过真正的异步接收入口触发超时，确认第一次重试、第二次明确报告停滞，绝不输出普通无路结论。 */
public final class PathCalculationStallTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        var backend = (Baritone) memory.allocateInstance(Baritone.class);
        var behavior = new PathingBehavior(backend);
        var receive = PathingBehavior.class.getDeclaredMethod("acceptCalculatedPath"); receive.setAccessible(true);
        productiveOldRequestIsNotRecovered(behavior, receive);
        var release = new CountDownLatch(1);
        int size = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 2));
        var started = new CountDownLatch(size); var requests = new ArrayList<CompletableFuture<Integer>>();
        try {
            for (int i = 0; i < size; i++) requests.add(PathPlannerPool.submit(() -> blocked(started, release)));
            check(started.await(5, TimeUnit.SECONDS), "stalled workers started");
            field(PathingBehavior.class, "pendingCalculation").set(behavior, requests.getFirst());
            field(PathingBehavior.class, "calculationTimeoutMillis").setLong(behavior, -1);
            receive.invoke(behavior);
            var events = (LinkedBlockingQueue<?>) field(PathingBehavior.class, "toDispatch").get(behavior);
            check(!events.contains(PathEvent.CALC_FAILED) && !behavior.calculationStalled(), "first stall retries without a fake no-path failure");
            check(behavior.searchDiagnostics().get("recovery_count").equals(1), "recovery remains visible in default diagnosis");
            check(PathPlannerPool.submit(() -> 42).get(5, TimeUnit.SECONDS) == 42, "new computation proceeds before stuck old readers exit");
            var again = new CountDownLatch(1);
            field(PathingBehavior.class, "pendingCalculation").set(behavior, PathPlannerPool.submit(() -> blocked(again, release)));
            check(again.await(5, TimeUnit.SECONDS), "replacement attempt started");
            receive.invoke(behavior);
            check(events.contains(PathEvent.CALC_FAILED) && behavior.calculationStalled()
                    && behavior.searchDiagnostics().get("classification").equals("planning_stall"),
                    "exhausted recovery settles with a planning-stall classification and evidence");
        } finally {
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (PathPlannerPool.activeThreads() > 0 && System.nanoTime() < deadline) Thread.sleep(1);
        }
        System.out.println("PathCalculationStallTest: retry and typed exhaustion passed");
    }
    private static int blocked(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        while (release.getCount() != 0) try { release.await(); } catch (InterruptedException ignored) { }
        return 0;
    }

    /** 长计算的总年龄已超过一分钟，但刚核实新节点，实际接收入口不能把它回收为停滞任务。 */
    private static void productiveOldRequestIsNotRecovered(PathingBehavior behavior, Method receive) throws Exception {
        var ready = new CountDownLatch(1); var release = new CountDownLatch(1);
        var work = PathPlannerPool.submit(() -> {
            PathPlannerPool.madeProgress("node_expansion"); ready.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return 0;
        });
        try {
            check(ready.await(5, TimeUnit.SECONDS), "productive request starts");
            field(work.getClass(), "submitted").setLong(work, System.nanoTime() - TimeUnit.SECONDS.toNanos(60));
            field(PathingBehavior.class, "pendingCalculation").set(behavior, work);
            field(PathingBehavior.class, "calculationTimeoutMillis").setLong(behavior, 10000);
            receive.invoke(behavior);
            check(!work.isCancelled() && behavior.searchDiagnostics().get("recovery_count").equals(0),
                    "a progress-refilled request is neither recovered nor failed because of total age");
        } finally {
            field(PathingBehavior.class, "pendingCalculation").set(behavior, null); release.countDown(); work.get(5, TimeUnit.SECONDS);
        }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
