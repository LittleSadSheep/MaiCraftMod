// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.behavior.travel.TravelFakes.FakePlaced;
import org.maiwithu.maicraft.behavior.travel.TravelFakes.FakePlaces;
import org.maiwithu.maicraft.behavior.travel.TravelFakes.FakeSeen;
import org.maiwithu.maicraft.behavior.travel.TravelFakes.FakeWalks;
import org.maiwithu.maicraft.behavior.travel.TravelFakes.FakeWorld;
import org.maiwithu.maicraft.behavior.travel.TravelFakes.ProgressRecorder;
import org.maiwithu.maicraft.behavior.travel.TravelFakes.ScriptedWalk;
import org.maiwithu.maicraft.behavior.travel.TravelFakes.TestTick;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 出行任务：拿着解析好的目的地上路，到达、中途停下、失败与打断后的接续都按现场如实结算。 */
class TravelTaskTest {

    private static final BlockPos HOME = new BlockPos(12, 64, -2);

    private final FakeWorld world = new FakeWorld();
    private final FakePlaces places = new FakePlaces();
    private final FakeSeen seen = new FakeSeen();
    private final FakeWalks walks = new FakeWalks();
    private final FakePlaced placed = new FakePlaced();
    private final ProgressRecorder progress = new ProgressRecorder();
    private final TerrainPermit permit = TerrainPermit.NATURAL;
    private final TestTick tick = new TestTick();

    private TravelTask task(Target target) {
        DestinationResolver resolver = new DestinationResolver(world, places, seen);
        var ready = assertInstanceOf(DestinationResolver.Resolution.Ready.class, resolver.resolve(target, 2));
        return new TravelTask(ready.destination(), 0, permit, walks, placed, progress);
    }

    /** 推进到出结果为止（最多几十刻），返回最终结果。 */
    private TaskResult runToFinish(TravelTask task) {
        task.start(tick);
        for (int i = 0; i < 50; i++) {
            TickResult result = task.tick(tick);
            if (result instanceof TickResult.Finished finished) {
                return finished.result();
            }
            tick.nextTick();
        }
        throw new AssertionError("出行任务没有按脚本收场");
    }

    @Test
    void walksToFullPositionAndReportsArrival() {
        walks.enqueue(new ScriptedWalk(
                WalkReport.planning(),
                WalkReport.onTheWay(new BlockPos(4, 64, 0), false),
                WalkReport.arrived(HOME, false)));
        TaskResult result = runToFinish(task(new Target.Position(12, 64, -2, null)));

        assertEquals(TaskResult.Status.DONE, result.status());
        TravelSettlement details = assertInstanceOf(TravelSettlement.class, result.details());
        assertEquals(new WorldPosition(12, 64, -2, null), details.arrivedAt());
        // 目的地交给走到时带上了许可；给全 y 且容差 2，按位置附近 2 格走。
        assertEquals(permit, walks.permits.get(0));
        assertEquals(2, ((NavGoal.Near) TravelFakes.goalOf(walks.started.get(0))).radius);
        assertTrue(progress.received.size() >= 2, "逐刻报告进展");
    }

    @Test
    void positionWithoutHeightHandsColumnGoalToWalkTo() {
        // 高度纪律：没给 y 的坐标交给走到的是柱列目标，不是猜出来的高度。
        walks.enqueue(new ScriptedWalk(WalkReport.arrived(HOME, false)));
        TaskResult result = runToFinish(task(new Target.Position(12, null, -2, null)));

        assertEquals(TaskResult.Status.DONE, result.status());
        assertInstanceOf(NavGoal.Column.class, TravelFakes.goalOf(walks.started.get(0)));
    }

    @Test
    void cancelStopsSafelyThenSettlesWithRemaining() {
        ScriptedWalk walk = new ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(4, 64, 0), false),
                WalkReport.onTheWay(new BlockPos(6, 64, -1), false),
                WalkReport.stopped(new BlockPos(6, 64, -1), false))
                .stopAnswers(false, true);
        walks.enqueue(walk);
        TravelTask travel = task(new Target.Position(12, 64, -2, null));
        travel.start(tick);
        assertTrue(travel.tick(tick) instanceof TickResult.Running);
        tick.nextTick();

        travel.requestStop();
        assertTrue(travel.tick(tick) instanceof TickResult.Running, "还没停稳就继续走完停下的动作");
        tick.nextTick();
        TickResult lastTick = travel.tick(tick);

        assertEquals(TaskResult.Status.CANCELLED, ((TickResult.Finished) lastTick).result().status());
        TaskResult result = ((TickResult.Finished) lastTick).result();
        assertTrue(walk.stopRequests >= 2, "先请求停，落到安全边界才真停");
        TravelSettlement details = assertInstanceOf(TravelSettlement.class, result.details());
        assertEquals(null, details.arrivedAt(), "没走到就没有到达位置");
        assertNotNull(details.remaining(), "没走到要写明还差多远");
        assertEquals("东 6.1 格、同高", details.remaining().describe());
        assertTrue(result.remaining().get(0).contains("还没走到目的地"));
    }

    @Test
    void survivalInterruptionResumesWalkingFromSameSpot() {
        ScriptedWalk firstLeg = new ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(2, 64, 0), false),
                WalkReport.stopped(new BlockPos(4, 64, -1), false));
        ScriptedWalk secondLeg = new ScriptedWalk(WalkReport.arrived(HOME, false));
        walks.enqueue(firstLeg);
        walks.enqueue(secondLeg);
        TravelTask travel = task(new Target.Position(12, 64, -2, null));
        travel.start(tick);
        assertTrue(travel.tick(tick) instanceof TickResult.Running);

        // 生存需求打断：走到运行先收到暂停请求；回来后从原地接着走。
        travel.pause();
        assertTrue(firstLeg.pauseRequests >= 1, "打断时暂停交给走到运行处理");
        TaskResult result = runToFinish(travel);

        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(2, walks.started.size(), "被打断后从原地重新上路");
    }

    @Test
    void navigationFailureKeepsProblemAndJourneyFacts() {
        walks.enqueue(new ScriptedWalk(
                WalkReport.failed(Problem.of(Problem.Kind.UNREACHABLE, "路被水挡住，没有允许动土"),
                        new BlockPos(3, 64, 1), true)));
        TaskResult result = runToFinish(task(new Target.Position(12, 64, -2, null)));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.UNREACHABLE, result.problem().kind());
        TravelSettlement details = assertInstanceOf(TravelSettlement.class, result.details());
        assertNotNull(details.remaining());
        assertTrue(details.crossedWater(), "泅渡过的事实如实进结果");
    }

    @Test
    void placedBlocksAreReportedNotCollected() {
        placed.put(new BlockPos(5, 65, 0));
        placed.put(new BlockPos(6, 66, -1));
        walks.enqueue(new ScriptedWalk(WalkReport.arrived(HOME, false)));
        TaskResult result = runToFinish(task(new Target.Position(12, 64, -2, null)));

        assertEquals(TaskResult.Status.DONE, result.status());
        long placedChanges = result.changes().stream()
                .filter(change -> change.kind() == Change.Kind.BLOCK_PLACED)
                .count();
        assertEquals(2, placedChanges, "垫的临时方块逐格进变化");
        TravelSettlement details = assertInstanceOf(TravelSettlement.class, result.details());
        assertEquals(List.of("5, 65, 0", "6, 66, -1"), details.placedBlocks());
        assertTrue(result.changes().get(0).note().contains("没有自动收回"));
    }

    @Test
    void crossedWaterIsReportedOnArrival() {
        walks.enqueue(new ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(4, 63, 0), true),
                WalkReport.arrived(HOME, true)));
        TaskResult result = runToFinish(task(new Target.Position(12, 64, -2, null)));

        assertEquals(TaskResult.Status.DONE, result.status());
        TravelSettlement details = assertInstanceOf(TravelSettlement.class, result.details());
        assertTrue(details.crossedWater());
        assertTrue(result.summary().contains("泅渡"));
    }
}
