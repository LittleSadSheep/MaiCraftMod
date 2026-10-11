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
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.TickResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        return task(target, permit);
    }

    private TravelTask task(Target target, TerrainPermit taskPermit) {
        DestinationResolver resolver = new DestinationResolver(world, places, seen);
        var ready = assertInstanceOf(DestinationResolver.Resolution.Ready.class, resolver.resolve(target, 2));
        return new TravelTask(ready.destination(), 0, taskPermit, walks, placed, progress);
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

    /** 推进到出结果为止，刻数上限给足空转收场用。 */
    private TaskResult runToFinish(TravelTask task, int maxTicks) {
        task.start(tick);
        for (int i = 0; i < maxTicks; i++) {
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
    void columnDestinationRemainingIgnoresPlaceholderHeight() {
        // 只给 x/z 的出行走不通时，还差多远按脚下这层量：实机里曾写成"低 64 格"，把占位的 y=0 当成了真高度。
        walks.enqueue(new ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(6, 70, -2), false),
                WalkReport.failed(Problem.of(Problem.Kind.UNREACHABLE, "没有能走到目标的路线"),
                        new BlockPos(6, 70, -2), false)));
        TaskResult result = runToFinish(task(new Target.Position(12, null, -2, null)));

        TravelSettlement details = assertInstanceOf(TravelSettlement.class, result.details());
        assertNotNull(details.remaining());
        assertEquals(0, details.remaining().verticalBlocks(), "高度没给就不报高低差");
        assertEquals("东 6.0 格、同高", details.remaining().describe());
        assertTrue(result.remaining().get(0).contains("差 东 6.0 格、同高"));
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
    void idleAtUnreachableHeightExplainsTargetHeightAndStandableCell() {
        // 目标 y=300 到不了：身体已经在那一列，空转满时限的收场要写清目标高度与找不到能站的格子。
        walks.enqueue(new ScriptedWalk(WalkReport.onTheWay(new BlockPos(-5, 178, 36), false)));
        TravelTask travel = task(new Target.Position(-5, 300, 36, null));

        TaskResult result = runToFinish(travel, 700);

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.UNREACHABLE, result.problem().kind(),
                "高度不可达是走不到，不是一句通用卡住");
        assertTrue(result.problem().message().contains("(-5, 300, 36)"), () -> "说明：" + result.problem().message());
        assertTrue(result.problem().message().contains("脚下 y=178"), () -> "说明：" + result.problem().message());
        assertTrue(result.problem().message().contains("找不到能站的格子"), () -> "说明：" + result.problem().message());
        assertNotNull(result.problem().suggestion());
        assertTrue(result.problem().suggestion().contains("y≈178"), () -> "建议：" + result.problem().suggestion());
        TravelSettlement details = assertInstanceOf(TravelSettlement.class, result.details());
        assertNotNull(details.remaining(), "没走到要写明还差多远");
    }

    @Test
    void idleMidRouteWithoutDigPermitReportsUnreachableAndSuggestsPermit() {
        // 不许动土、停在路上不走也不失败：空转满时限按 UNREACHABLE 收场，建议里写明许可动土能过。
        walks.enqueue(new ScriptedWalk(WalkReport.onTheWay(new BlockPos(4, 64, 0), false)));
        TravelTask travel = task(new Target.Position(12, 64, -2, null), TerrainPermit.WALK_ONLY);

        TaskResult result = runToFinish(travel, 700);

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.UNREACHABLE, result.problem().kind());
        assertTrue(result.problem().message().contains("停在 (4, 64, 0)"), () -> "说明：" + result.problem().message());
        assertTrue(result.problem().message().contains("change_blocks=none"), () -> "说明：" + result.problem().message());
        assertTrue(result.problem().message().contains("剩 东 8.2 格"), () -> "说明：" + result.problem().message());
        assertTrue(result.problem().suggestion().contains("动土"), () -> "建议：" + result.problem().suggestion());
    }

    @Test
    void externalCancelCarriesStoppedSpotRemainingAndDetails() {
        // 走到一半目标被取消：结论带停在哪、剩多远与行程细节，垫过的方块也已经在变化里。
        placed.put(new BlockPos(5, 65, 0));
        walks.enqueue(new ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(4, 64, 0), false),
                WalkReport.onTheWay(new BlockPos(6, 64, -1), false)));
        TravelTask travel = task(new Target.Position(12, 64, -2, null));
        travel.start(tick);
        assertTrue(travel.tick(tick) instanceof TickResult.Running);
        tick.nextTick();
        assertTrue(travel.tick(tick) instanceof TickResult.Running);

        TaskResult result = travel.close(CloseReason.CANCELLED);

        assertEquals(TaskResult.Status.CANCELLED, result.status());
        // 停在哪、差多远进 remaining，由目标收尾并进取消的结论（见 GoalRunnerTest 的取消现场传播）。
        assertTrue(result.remaining().get(0).contains("停在 (6, 64, -1)"));
        assertTrue(result.remaining().get(0).contains("差 东 6.1 格、同高"));
        TravelSettlement details = assertInstanceOf(TravelSettlement.class, result.details());
        assertNull(details.arrivedAt(), "取消不是到达");
        assertNotNull(details.remaining());
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.BLOCK_PLACED),
                "取消前垫的方块进变化");
    }

    @Test
    void itemsPickedUpAlongTheWayAreRecordedAsChanges() {
        // 路上顺带捡进背包的东西逐刻进变化，不用等事后翻背包才发现。
        TravelFakes.FakeBag bag = new TravelFakes.FakeBag();
        bag.put("minecraft:stick", 10);
        tick.withPlayer(new TravelFakes.FakePlayer(bag));
        walks.enqueue(new ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(4, 64, 0), false),
                WalkReport.onTheWay(new BlockPos(6, 64, -1), false),
                WalkReport.arrived(HOME, false)));
        TravelTask travel = task(new Target.Position(12, 64, -2, null));
        travel.start(tick);
        assertTrue(travel.tick(tick) instanceof TickResult.Running, "第一刻记下背包基线");
        tick.nextTick();
        bag.put("minecraft:dirt", 6);
        assertTrue(travel.tick(tick) instanceof TickResult.Running, "第二刻对比出捡到的泥土");

        TaskResult result = runToFinish(travel);

        assertEquals(TaskResult.Status.DONE, result.status());
        Change dirt = result.changes().stream()
                .filter(change -> "minecraft:dirt".equals(change.what()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("捡到的泥土没进变化：" + result.changes()));
        assertEquals(Change.Kind.ITEM_GAINED, dirt.kind());
        assertEquals(6, dirt.count());
    }

    @Test
    void bagDiffRecordsGainsAndConsumes() {
        List<Change> changes = TravelTask.bagChanges(
                Map.of("minecraft:stick", 10),
                Map.of("minecraft:stick", 7, "minecraft:dirt", 6));

        assertEquals(2, changes.size());
        Change gained = changes.get(0);
        assertEquals(Change.Kind.ITEM_GAINED, gained.kind());
        assertEquals("minecraft:dirt", gained.what());
        assertEquals(6, gained.count());
        Change consumed = changes.get(1);
        assertEquals(Change.Kind.ITEM_CONSUMED, consumed.kind());
        assertEquals("minecraft:stick", consumed.what());
        assertEquals(3, consumed.count());
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
