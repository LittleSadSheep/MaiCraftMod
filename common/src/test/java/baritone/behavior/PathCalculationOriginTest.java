package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.PathEvent;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.PathCalculationResult;
import baritone.pathing.path.PathExecutor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import sun.misc.Unsafe;

/** 在真正的异步结果接收入口回放传送、换世界和改目标，防止旧无路事件终止当前任务。 */
public final class PathCalculationOriginTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (String scenario : List.of("same", "teleport", "world", "goal", "ahead", "stale_ahead")) {
            try (var w = new InteractionWorldTestHarness()) {
                var memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
                ClientLevel[] live = {w.level};
                var context = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                        new Class<?>[]{IPlayerContext.class}, (proxy, method, values) -> {
                            if (method.getName().equals("world")) return live[0];
                            throw new AssertionError(method.getName());
                        });
                var backend = (Baritone) memory.allocateInstance(Baritone.class);
                field(Baritone.class, "playerContext").set(backend, context);
                var behavior = new PathingBehavior(backend);
                var start = new BetterBlockPos(2, 1, 3);
                var goal = new GoalBlock(10, 1, 3);
                field(PathingBehavior.class, "goal").set(behavior, goal);
                field(PathingBehavior.class, "calculationGoal").set(behavior, goal);
                field(PathingBehavior.class, "calculationWorld").set(behavior, w.level);
                field(PathingBehavior.class, "calculationStart").set(behavior, start);
                field(PathingBehavior.class, "expectedSegmentStart").set(behavior,
                        scenario.equals("teleport") ? new BetterBlockPos(start.offset(8, 0, 0)) : start);
                if (scenario.equals("world")) live[0] = null;
                if (scenario.equals("goal")) field(PathingBehavior.class, "goal").set(behavior, new GoalBlock(14, 1, 3));
                // 前瞻是从当前路段终点出发，不能误拿玩家此刻的位置否决有效的下一段失败。
                if (scenario.endsWith("ahead")) {
                    BlockPos destination = scenario.equals("stale_ahead") ? start.offset(8, 0, 0) : start;
                    var executor = (Executor) memory.allocateInstance(Executor.class);
                    executor.path = (IPath) Proxy.newProxyInstance(IPath.class.getClassLoader(), new Class<?>[]{IPath.class},
                            (proxy, method, values) -> {
                                if (method.getName().equals("getDest")) return new BetterBlockPos(destination);
                                throw new AssertionError(method.getName());
                            });
                    field(PathingBehavior.class, "current").set(behavior, executor);
                }
                field(PathingBehavior.class, "pendingCalculation").set(behavior,
                        CompletableFuture.completedFuture(new PathCalculationResult(PathCalculationResult.Type.FAILURE)));
                var receive = PathingBehavior.class.getDeclaredMethod("acceptCalculatedPath"); receive.setAccessible(true); receive.invoke(behavior);
                var events = (LinkedBlockingQueue<?>) field(PathingBehavior.class, "toDispatch").get(behavior);
                check(events.contains(PathEvent.CALC_FAILED) == scenario.equals("same"), "current-route failure belongs only to unchanged origin: " + scenario);
                if (scenario.equals("same")) check(behavior.searchDiagnostics().get("classification").equals("planning_no_solution"),
                        "a completed search with no route is distinct from an unreturned calculation");
                check(events.contains(PathEvent.NEXT_CALC_FAILED) == scenario.equals("ahead"), "ahead failure belongs only to unchanged segment end: " + scenario);
                check(field(PathingBehavior.class, "pendingCalculation").get(behavior) == null,
                        "discarded result releases the worker slot so the current goal can search again");
                check(field(PathingBehavior.class, "goal").get(behavior) != null, "discarding evidence must not discard the requested destination");
            }
        }
        System.out.println("PathCalculationOriginTest: passed");
    }

    // 移动执行留给游戏，此处只提供正在行走路段的真实终点语义以检查前瞻归属。
    private static final class Executor extends PathExecutor {
        IPath path;
        private Executor() { super(null, null); }
        @Override public IPath getPath() { return path; }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
