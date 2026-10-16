// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.DigsStairsUp;
import org.maiwithu.maicraft.behavior.explore.ColumnSample;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 回露天：露天落脚处按远近找；旁边几步见天先走出去；地下许可动土就挖楼梯；楼梯挖不下去或不许动土找路走；
 * 都不行如实说找了多大范围。
 */
class ReachOpenSkyTaskTest {

    /** 现场替身：地面默认在 69 层（脚站 70 层），个别列可以另摆顶盖高度。 */
    private static final class Sky implements TravelWorldView {
        WorldPosition spot = WorldPosition.here(0, 40, 0);
        final Map<Long, Integer> tops = new HashMap<>();
        int ground = 69;

        void top(int x, int z, int y) {
            tops.put(BlockPos.asLong(x, 0, z), y);
        }

        void standAt(BlockPos feet) {
            spot = WorldPosition.here(feet.getX(), feet.getY(), feet.getZ());
        }

        @Override public WorldPosition currentSpot() {
            return spot;
        }
        @Override public Target.Toward currentFacing() {
            return Target.Toward.NORTH;
        }
        @Override public ColumnSample column(int x, int z) {
            int top = tops.getOrDefault(BlockPos.asLong(x, 0, z), ground);
            return new ColumnSample(true, "minecraft:plains", top, false, true, "minecraft:stone");
        }
    }

    /** 往上挖楼梯替身：演一刻就到地面（或按给的问题挖不下去），记下被叫了几次。 */
    private final class Stairs implements DigsStairsUp {
        int calls;
        Problem stops;

        @Override public Action digUp(Predicate<BlockPos> outside, Permissions permissions, TaskRecords records) {
            calls++;
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    if (stops != null) return ActionStatus.failed(stops);
                    records.change(new Change(Change.Kind.BLOCK_BROKEN, "minecraft:stone", 87, "往上挖楼梯挖掉的（29 级）"));
                    sky.standAt(new BlockPos(0, 70, -30));
                    return ActionStatus.done();
                }
                @Override public String describe() {
                    return "往上挖楼梯";
                }
            };
        }
    }

    /** 走到替身：每次开始走取一段脚本，走到了就把人挪到到达的那一格；脚本取完后复用最后一段。 */
    private final class Walks implements WalkTo {
        final List<GoalCompiler.Compiled> started = new ArrayList<>();
        private final Deque<WalkReport> scripts = new ArrayDeque<>();
        private WalkReport last;

        void enqueue(WalkReport ending) {
            scripts.add(ending);
        }

        @Override public WalkRun start(GoalCompiler.Compiled target, TerrainPermit permit) {
            started.add(target);
            WalkReport ending = scripts.isEmpty() ? last : scripts.remove();
            last = ending;
            return new WalkRun() {
                private WalkReport report = WalkReport.planning();
                @Override public ActionStatus tick(TickContext context) {
                    report = ending;
                    if (ending.state() == WalkReport.State.FAILED) return ActionStatus.failed(ending.problem());
                    sky.standAt(ending.feet());
                    return ActionStatus.done();
                }
                @Override public WalkReport report() {
                    return report;
                }
                @Override public boolean stop() {
                    return true;
                }
                @Override public String describe() {
                    return "演出来的走到";
                }
            };
        }
    }

    private final Sky sky = new Sky();
    private final Stairs stairs = new Stairs();
    private final Walks walks = new Walks();

    private TaskResult run(Permissions permissions) {
        ReachOpenSkyTask task = new ReachOpenSkyTask(0, permissions, sky, walks, stairs, new TravelFakes.FakePlaced());
        TravelFakes.TestTick tick = new TravelFakes.TestTick();
        task.start(tick);
        for (int i = 0; i < 2000; i++) {
            TickResult result = task.tick(tick);
            if (result instanceof TickResult.Finished finished) return finished.result();
            tick.nextTick();
        }
        throw new AssertionError("回露天一直没收场");
    }

    private static Permissions changes(Permissions.BlockChanges changes) {
        return Permissions.DEFAULT.mergedWith(changes, null, null, null, null, null);
    }

    @Test
    void 露天落脚处_近处只看走几步就到的_远处每四格看一列() {
        // 人在 70 层的崖檐下：顶盖在 74 层，往东三格起见天。
        sky.standAt(new BlockPos(0, 70, 0));
        for (int z = -8; z <= 8; z++) for (int x = -8; x <= 2; x++) sky.top(x, z, 74);

        List<BlockPos> near = OpenSkySpots.near(sky, new BlockPos(0, 70, 0));
        assertEquals(new BlockPos(3, 70, 0), near.getFirst(), "最近的露天落脚处在前");
        assertTrue(near.stream().noneMatch(spot -> spot.getX() <= 2), "崖檐顶上高了 5 格，不算走几步就到");
        assertFalse(OpenSkySpots.underOpenSky(sky, new BlockPos(0, 70, 0)));
        assertTrue(OpenSkySpots.underOpenSky(sky, new BlockPos(3, 70, 0)));

        List<BlockPos> far = OpenSkySpots.far(sky, new BlockPos(0, 40, 0), Set.of());
        assertTrue(far.stream().allMatch(spot -> spot.getX() % 4 == 0 && spot.getZ() % 4 == 0), "远处每隔四格看一列");
        assertTrue(far.size() <= OpenSkySpots.MAX_SPOTS);
    }

    @Test
    void 崖檐下_旁边几步见天先走出去_不挖楼梯() {
        sky.standAt(new BlockPos(0, 70, 0));
        for (int z = -8; z <= 8; z++) for (int x = -8; x <= 2; x++) sky.top(x, z, 74);
        walks.enqueue(WalkReport.arrived(new BlockPos(3, 70, 0), false));

        TaskResult result = run(Permissions.DEFAULT);

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(0, stairs.calls, "旁边见天就走出去，不挖");
        assertTrue(walks.started.getFirst().goal() instanceof NavGoal.Composite, "交给走到几处露天落脚处挑一处");
    }

    @Test
    void 深处许可动土_挖楼梯上去() {
        TaskResult result = run(Permissions.DEFAULT);

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(1, stairs.calls);
        assertTrue(walks.started.isEmpty(), "近处没有露天落脚处，直接挖楼梯");
        assertTrue(result.summary().contains("挖楼梯上来"), result.summary());
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.BLOCK_BROKEN
                && change.count() == 87), result.changes().toString());
        TravelSettlement settlement = (TravelSettlement) result.details();
        assertEquals(70, settlement.arrivedAt().y());
    }

    @Test
    void 楼梯挖不下去_找路走到远处露天落脚处() {
        stairs.stops = Problem.of(Problem.Kind.DANGER, "往上挖楼梯：头顶上面压着沙砾，挖开会砸下来");
        walks.enqueue(WalkReport.arrived(new BlockPos(4, 70, 0), false));

        TaskResult result = run(Permissions.DEFAULT);

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertTrue(result.attempts().stream().anyMatch(attempt -> attempt.tried().equals("往上挖楼梯")
                && attempt.result().contains("沙砾")), result.attempts().toString());
        assertEquals(1, walks.started.size());
    }

    @Test
    void 不许动土又走不出去_如实说找了多大范围并建议放开许可() {
        Problem blocked = Problem.of(Problem.Kind.UNREACHABLE, "找不到路");
        walks.enqueue(WalkReport.failed(blocked, new BlockPos(0, 40, 0), false));

        TaskResult result = run(changes(Permissions.BlockChanges.NONE));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(0, stairs.calls, "不许动土不挖楼梯");
        assertEquals(ReachOpenSkyTask.FAR_WALKS, walks.started.size(), "最多换三回落脚处");
        Problem problem = result.problem();
        assertEquals(Problem.Kind.UNREACHABLE, problem.kind());
        assertTrue(problem.message().contains("64 格"), problem.message());
        assertTrue(problem.suggestion().contains("change_blocks=natural"), problem.suggestion());
        // 三回失败的说明在 attempts 里合成一条、写明又出现几次；问题只说结论，不再抄一遍。
        assertTrue(problem.message().contains("走了 3 回"), problem.message());
        assertFalse(problem.message().contains("找不到路"), problem.message());
        assertEquals(1, result.attempts().size(), result.attempts().toString());
        assertTrue(result.attempts().getFirst().result().contains("又出现 2 次"), result.attempts().toString());
        assertTrue(problem.message().length() < 200, problem.message());
    }

    @Test
    void 只许垫临时方块_也不挖楼梯() {
        walks.enqueue(WalkReport.failed(Problem.of(Problem.Kind.UNREACHABLE, "找不到路"), new BlockPos(0, 40, 0), false));

        run(changes(Permissions.BlockChanges.TEMPORARY));

        assertEquals(0, stairs.calls);
    }
}
