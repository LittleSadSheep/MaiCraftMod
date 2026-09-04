package baritone.pathing.calc;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.core.pathing.calc.PathPlannerPool;

/** Exercises the production cancellation boundary, parent traversal and published preview API. */
public final class PathSearchRegressionTest {
    private static final Goal GOAL = new Goal() {
        public boolean isInGoal(int x, int y, int z) { return false; }
        public double heuristic(int x, int y, int z) { return 0; }
    };

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        cancelledBeforeDispatchNeverStarts();
        activeSearchSeesCancellation();
        previewsDoNotTraverseMutableParents();
        cyclicPathFailsWithoutHanging();
        workerPoolNeverRunsSearchOnCaller();
        System.out.println("PathSearchRegressionTest: passed");
    }

    private static void cancelledBeforeDispatchNeverStarts() {
        Search search = new Search(false);
        search.cancel();
        PathCalculationResult result = search.calculate(100, 100);
        check(result.getType() == PathCalculationResult.Type.CANCELLATION,
                "a queued cancellation must survive calculate()");
        check(search.started.getCount() == 1 && search.isFinished(),
                "a cancelled queued search must settle without expanding nodes");
    }

    private static void activeSearchSeesCancellation() throws Exception {
        Search search = new Search(true);
        CompletableFuture<PathCalculationResult> result = PathPlannerPool.submit(() -> search.calculate(100, 100));
        check(search.started.await(2, TimeUnit.SECONDS), "worker did not start");
        search.cancel();
        check(result.get(2, TimeUnit.SECONDS).getType() == PathCalculationResult.Type.CANCELLATION,
                "running worker must observe cancellation");
    }

    private static void previewsDoNotTraverseMutableParents() {
        Search search = new Search(false);
        PathNode end = search.publishStraightPath();
        IPath snapshot = search.bestPathSoFar().orElseThrow();
        check(snapshot.positions().size() == 7, "preview lost part of the path");
        end.previous = end;
        check(search.bestPathSoFar().orElseThrow() == snapshot,
                "client preview must reuse the worker's published snapshot");
        check(search.pathToMostRecentNodeConsidered().orElseThrow().positions().size() == 7,
                "render preview must not follow a subsequently changed parent link");
        try {
            snapshot.positions().clear();
            throw new AssertionError("preview positions must be immutable");
        } catch (UnsupportedOperationException expected) { }
    }

    private static void cyclicPathFailsWithoutHanging() throws Exception {
        PathNode start = new PathNode(0, 0, 0, GOAL);
        PathNode end = new PathNode(1, 0, 0, GOAL);
        start.previous = end;
        end.previous = start;
        CompletableFuture.runAsync(() -> {
            try {
                new Path(new BetterBlockPos(0, 0, 0), start, end, 2, GOAL, null);
                throw new AssertionError("cyclic parent links must fail path assembly");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().contains("Cycle"), "unexpected path failure");
            }
        }).get(2, TimeUnit.SECONDS);
    }

    private static void workerPoolNeverRunsSearchOnCaller() throws Exception {
        Thread caller = Thread.currentThread();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger callerRuns = new AtomicInteger();
        List<CompletableFuture<Integer>> pending = new ArrayList<>();
        try {
            for (int i = 0; i < 64; i++) {
                pending.add(PathPlannerPool.submit(() -> {
                    if (Thread.currentThread() == caller) callerRuns.incrementAndGet();
                    try {
                        if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("worker not released");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                    return 1;
                }));
            }
            check(pending.stream().anyMatch(CompletableFuture::isCompletedExceptionally),
                    "worker queue must reject excess work instead of retaining unlimited generations");
            check(PathPlannerPool.peakThreads() <= 2 && callerRuns.get() == 0,
                    "path calculations must remain bounded and off the client thread");
        } finally {
            release.countDown();
        }
        for (CompletableFuture<Integer> future : pending) {
            if (!future.isCompletedExceptionally()) future.get(2, TimeUnit.SECONDS);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Search extends AbstractNodeCostSearch {
        private final CountDownLatch started = new CountDownLatch(1);
        private final boolean waitForCancellation;

        private Search(boolean waitForCancellation) {
            super(new BetterBlockPos(0, 0, 0), 0, 0, 0, GOAL, null, 16, 0.75f);
            this.waitForCancellation = waitForCancellation;
        }

        @Override
        protected Optional<IPath> calculate0(long primaryTimeout, long failureTimeout) {
            started.countDown();
            if (waitForCancellation) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!cancelRequested && System.nanoTime() < deadline) Thread.onSpinWait();
                check(cancelRequested, "worker never observed the cancel flag");
            }
            return Optional.empty();
        }

        private PathNode publishStraightPath() {
            startNode = new PathNode(0, 0, 0, GOAL);
            PathNode end = startNode;
            for (int x = 1; x <= 6; x++) {
                PathNode next = new PathNode(x, 0, 0, GOAL);
                next.previous = end;
                end = next;
            }
            bestSoFar[0] = end;
            mostRecentConsidered = end;
            publishPathSnapshots(7);
            return end;
        }
    }
}
