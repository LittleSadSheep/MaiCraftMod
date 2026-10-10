// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.CollectsBlocks;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 睡觉任务的离线场景：选中的床走近躺下即完成；床被占用换下一张；
 * 附近没床时放下自带的床或先弄一张；夜休睡醒后收床回站位。
 */
class SleepTaskTest {

    private static final BlockPos BED_A = new BlockPos(4, 64, 2);
    private static final BlockPos BED_B = new BlockPos(9, 64, 7);

    // 各接缝的替身：按脚本给出扫描结果、动作状态与拒绝提示语。
    private static final class FakeScanner implements BedScanner {
        private final Deque<BedScan> scans = new ArrayDeque<>();
        List<Set<BlockPos>> askedExclusions = new ArrayList<>();

        void offer(BedScan scan) {
            scans.add(scan);
        }

        @Override
        public BedScan scan(TickContext context, Set<BlockPos> excluded,
                Set<String> protectedLandmarks) {
            askedExclusions.add(excluded);
            return scans.isEmpty() ? new BedScan(List.of(), true) : scans.remove();
        }
    }

    /** 一小段脚本动作：按次序返回状态，用完重复最后一个。 */
    private static final class Scripted implements Action {
        private final List<ActionStatus> script = new ArrayList<>();
        private int index;

        Scripted(ActionStatus... statuses) {
            script.addAll(List.of(statuses));
        }

        @Override
        public ActionStatus tick(TickContext context) {
            ActionStatus status = script.get(Math.min(index, script.size() - 1));
            index++;
            return status;
        }

        @Override public void pause() {}

        @Override public void close() {}

        @Override public String describe() {
            return "脚本动作";
        }
    }

    private static final class StubApproaches implements BringsPlayerClose {
        final List<BlockPos> targets = new ArrayList<>();
        private final Deque<Action> script = new ArrayDeque<>();

        void offer(Action action) {
            script.add(action);
        }

        @Override
        public Action toward(ApproachTarget target, Permissions permissions) {
            targets.add(target.anchorBlock());
            // 没脚本就一次成功：多数场景只关心"出发了、到了"。
            return script.isEmpty() ? new Scripted(ActionStatus.done()) : script.remove();
        }
    }

    private static final class StubUsesBeds implements UsesBeds {
        final List<BlockPos> clicked = new ArrayList<>();
        private final Deque<Action> script = new ArrayDeque<>();

        void offer(Action action) {
            script.add(action);
        }

        @Override
        public Action use(BlockPos head, InteractionConfirmation confirmation) {
            clicked.add(head);
            return script.isEmpty() ? new Scripted(ActionStatus.done()) : script.remove();
        }
    }

    private static final class StubObtain implements ItemNeeds {
        ItemRequest last;
        private final Deque<Action> script = new ArrayDeque<>();

        void offer(Action action) {
            script.add(action);
        }

        @Override
        public Action actionFor(ItemRequest request, Permissions permissions) {
            this.last = request;
            return script.isEmpty() ? new Scripted(ActionStatus.done()) : script.remove();
        }
    }

    /** 放床替身：放下后床头在给定的一格。 */
    private static final class Placed implements PlacesBed.BedPlacement {
        private final BlockPos head;
        private final Action scripted = new Scripted(ActionStatus.done());

        Placed(BlockPos head) {
            this.head = head;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            return scripted.tick(context);
        }

        @Override public void pause() {}

        @Override public void close() {}

        @Override public BlockPos placedHead() {
            return head;
        }

        @Override public String describe() {
            return "在身边放下床";
        }
    }

    private static final class StubPlacer implements PlacesBed {
        private BlockPos head;

        void offer(BlockPos head) {
            this.head = head;
        }

        @Override
        public Optional<BedPlacement> placeCarriedBed() {
            // 每次都要能新建：能力先问"放床这条路通不通"再进阶段，问的时候不能把替身用掉。
            return head == null ? Optional.empty() : Optional.of(new Placed(head));
        }
    }

    private static TickContext tick() {
        // 角色替身不接世界：维度爆炸检查与确认条件在这条任务路径上不会被触发。
        return new TickContext() {
            @Override public long gameTick() {
                return 100;
            }

            @Override public PlayerContext player() {
                return null;
            }
        };
    }

    private static SleepTask task(FakeScanner scanner, StubPlacer placer, StubObtain obtain,
            StubApproaches approaches, StubUsesBeds usesBeds,
            Supplier<Optional<String>> refusals,
            Supplier<Optional<String>> carried) {
        // 缺省可睡窗口全开、排除名单任务自持：多数场景不关心这两样。
        return task(scanner, placer, obtain, approaches, usesBeds, refusals, carried,
                moment -> true, new HashSet<>());
    }

    private static SleepTask task(FakeScanner scanner, StubPlacer placer, StubObtain obtain,
            StubApproaches approaches, StubUsesBeds usesBeds,
            Supplier<Optional<String>> refusals,
            Supplier<Optional<String>> carried, SleepTask.ReadsSleepWindow sleepWindow,
            Set<BlockPos> sharedExclusions) {
        return new SleepTask(new SleepInput(null), Permissions.DEFAULT, scanner, placer, obtain,
                approaches, usesBeds, refusals::get, carried::get, sleepWindow, sharedExclusions);
    }

    private static NightRestTask nightRest(FakeScanner scanner, StubApproaches approaches,
            StubUsesBeds usesBeds, SleepTask.ReadsSleepWindow sleepWindow, Set<BlockPos> sharedExclusions) {
        return new NightRestTask(new SleepInput(null), Permissions.DEFAULT, scanner, new StubPlacer(),
                new StubObtain(), approaches, usesBeds, Optional::empty, Optional::empty,
                (cell, permissions) -> Optional.empty(), place -> Optional.empty(), moment -> false,
                sleepWindow, sharedExclusions);
    }

    private static TaskResult runToCompletion(Task task) {
        TickContext context = tick();
        task.start(context);
        for (int i = 0; i < 200; i++) {
            TickResult result = task.tick(context);
            if (result instanceof TickResult.Finished finished) {
                return finished.result();
            }
        }
        throw new AssertionError("任务两百刻内没有结束");
    }

    @Test
    void walksToTheChosenBedAndFinishesByLyingDown() {
        FakeScanner scanner = new FakeScanner();
        scanner.offer(new BedScanner.BedScan(
                List.of(new BedCandidate(BED_A, false, false, false, 4)), true));
        StubApproaches approaches = new StubApproaches();
        StubUsesBeds usesBeds = new StubUsesBeds();
        TaskResult result = runToCompletion(task(scanner, new StubPlacer(), new StubObtain(),
                approaches, usesBeds, Optional::empty, Optional::empty));
        // 躺下即完成，床是世界里原有的。
        assertEquals(TaskResult.Status.DONE, result.status());
        SleepDetails details = assertInstanceOf(SleepDetails.class, result.details());
        assertTrue(details.enteredSleep());
        assertEquals(SleepDetails.BedSource.WORLD, details.bedSource());
        assertEquals(List.of(BED_A), usesBeds.clicked);
        assertEquals(List.of(BED_A), approaches.targets);
    }

    @Test
    void occupiedBedIsSkippedAndTheNextOneIsTried() {
        FakeScanner scanner = new FakeScanner();
        scanner.offer(new BedScanner.BedScan(
                List.of(new BedCandidate(BED_A, false, false, false, 4)), true));
        // 换床时再扫一遍，还能扫到第二张。
        scanner.offer(new BedScanner.BedScan(
                List.of(new BedCandidate(BED_B, false, false, false, 9)), true));
        StubUsesBeds usesBeds = new StubUsesBeds();
        usesBeds.offer(new Scripted(ActionStatus.failed(
                Problem.of(Problem.Kind.REFUSED_BY_GAME, "对床头的交互没有生效", null))));
        Supplier<Optional<String>> refusals = () -> Optional.of("这张床被占用");
        TaskResult result = runToCompletion(task(scanner, new StubPlacer(), new StubObtain(),
                new StubApproaches(), usesBeds, refusals, Optional::empty));
        // 被占用的床被排除，第二张床睡上了。
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(List.of(BED_A, BED_B), usesBeds.clicked);
        // 排除集跟着试过的床走：第二次扫描会带上床头 A。
        assertTrue(scanner.askedExclusions.getLast().contains(BED_A), "换床时排除试过的那张");
    }

    @Test
    void placesTheCarriedBedWhenNoBedIsNearby() {
        FakeScanner scanner = new FakeScanner();
        scanner.offer(new BedScanner.BedScan(List.of(), true));
        StubPlacer placer = new StubPlacer();
        BlockPos placedHead = new BlockPos(1, 64, 1);
        placer.offer(placedHead);
        StubUsesBeds usesBeds = new StubUsesBeds();
        TaskResult result = runToCompletion(task(scanner, placer, new StubObtain(),
                new StubApproaches(), usesBeds, Optional::empty, () -> Optional.of("minecraft:red_bed")));
        // 附近没床时放下自带的床，对着放好的床头睡。
        assertEquals(TaskResult.Status.DONE, result.status());
        SleepDetails details = assertInstanceOf(SleepDetails.class, result.details());
        assertEquals(SleepDetails.BedSource.CARRIED, details.bedSource());
        assertEquals(List.of(placedHead), usesBeds.clicked);
    }

    @Test
    void obtainsABedWhenNeitherWorldNorBackpackHasOne() {
        FakeScanner scanner = new FakeScanner();
        scanner.offer(new BedScanner.BedScan(List.of(), true));
        StubPlacer placer = new StubPlacer();
        BlockPos placedHead = new BlockPos(2, 64, 2);
        placer.offer(placedHead);
        StubObtain obtain = new StubObtain();
        TaskResult result = runToCompletion(task(scanner, placer, obtain,
                new StubApproaches(), new StubUsesBeds(), Optional::empty, Optional::empty));
        // 先去弄一张床（合成也在途径里），弄到后放下再睡；床的来源记成现做。
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals("#minecraft:beds", obtain.last.wanted().specifier());
        SleepDetails details = assertInstanceOf(SleepDetails.class, result.details());
        assertEquals(SleepDetails.BedSource.CRAFTED, details.bedSource());
    }

    @Test
    void prepareBedFinishesWhenTheBedIsOnTheBody() {
        StubObtain obtain = new StubObtain();
        PrepareBedTask prepare = new PrepareBedTask(obtain, Permissions.DEFAULT,
                () -> Optional.of("minecraft:white_bed"));
        TaskResult result = runToCompletion(prepare);
        assertEquals(TaskResult.Status.DONE, result.status());
    }

    @Test
    void prepareBedReportsWhyTheBedCouldNotBeObtained() {
        StubObtain obtain = new StubObtain();
        obtain.offer(new Scripted(ActionStatus.failed(
                Problem.of(Problem.Kind.NEED_ITEM, "做床还缺 3 块羊毛，附近没有羊", null))));
        PrepareBedTask prepare = new PrepareBedTask(obtain, Permissions.DEFAULT, Optional::empty);
        TaskResult result = runToCompletion(prepare);
        // 弄不到床如实失败，缺什么由拿到物品引擎交代。
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind());
        assertTrue(result.problem().message().contains("羊毛"));
    }

    @Test
    void nightRestKeepsWalkingWhileTheSleepWindowIsOpen() {
        // 回归 201：夜里可睡窗口没关，夜休任务在靠近阶段必须继续走，不许自称"这一夜已经过去"收工。
        FakeScanner scanner = new FakeScanner();
        scanner.offer(new BedScanner.BedScan(
                List.of(new BedCandidate(BED_A, false, false, false, 4)), true));
        StubApproaches approaches = new StubApproaches();
        approaches.offer(new Scripted(ActionStatus.running()));
        NightRestTask rest = nightRest(scanner, approaches, new StubUsesBeds(),
                moment -> true, new HashSet<>());
        TickContext context = tick();
        rest.start(context);
        for (int i = 0; i < 5; i++) {
            TickResult result = rest.tick(context);
            assertFalse(result instanceof TickResult.Finished, "可睡窗口还开着，夜休要在路上");
        }
        assertEquals(List.of(BED_A), approaches.targets);
    }

    @Test
    void nightRestEndsTheNightWhenTheSleepWindowCloses() {
        FakeScanner scanner = new FakeScanner();
        scanner.offer(new BedScanner.BedScan(
                List.of(new BedCandidate(BED_A, false, false, false, 4)), true));
        NightRestTask rest = nightRest(scanner, new StubApproaches(), new StubUsesBeds(),
                moment -> false, new HashSet<>());
        TaskResult result = runToCompletion(rest);
        // 窗口关了（天亮、被别人睡过）按夜休语义结清：算度过这一夜，不是失败。
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals("这一夜已经过去，不用睡了", result.summary());
        SleepDetails details = assertInstanceOf(SleepDetails.class, result.details());
        assertTrue(details.sleepCompletedExternally());
    }

    @Test
    void approachFailureRepositionsBeforeSwitchingBeds() {
        FakeScanner scanner = new FakeScanner();
        scanner.offer(new BedScanner.BedScan(
                List.of(new BedCandidate(BED_A, false, false, false, 4)), true));
        StubApproaches approaches = new StubApproaches();
        approaches.offer(new Scripted(ActionStatus.failed(
                Problem.of(Problem.Kind.UNREACHABLE, "去床的路上走不通", null))));
        StubUsesBeds usesBeds = new StubUsesBeds();
        TaskResult result = runToCompletion(task(scanner, new StubPlacer(), new StubObtain(),
                approaches, usesBeds, Optional::empty, Optional::empty));
        // 靠近失败先换一次站位再点，第二次走近成功躺下；不把这张床一棍子排除。
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(List.of(BED_A, BED_A), approaches.targets);
        assertEquals(List.of(BED_A), usesBeds.clicked);
    }

    @Test
    void triedBedIsRememberedAcrossNightRestReinserts() {
        // 夜休的需求会重插任务：睡不上的床写进跨任务黑名单，下一轮选床直接跳过它。
        Set<BlockPos> shared = new HashSet<>();
        FakeScanner scanner = new FakeScanner();
        scanner.offer(new BedScanner.BedScan(
                List.of(new BedCandidate(BED_A, false, false, false, 4)), true));
        scanner.offer(new BedScanner.BedScan(
                List.of(new BedCandidate(BED_B, false, false, false, 9)), true));
        StubUsesBeds usesBeds = new StubUsesBeds();
        usesBeds.offer(new Scripted(ActionStatus.failed(
                Problem.of(Problem.Kind.REFUSED_BY_GAME, "对床头的交互没有生效", null))));
        Supplier<Optional<String>> refusals = () -> Optional.of("这张床被占用");
        TaskResult first = runToCompletion(task(scanner, new StubPlacer(), new StubObtain(),
                new StubApproaches(), usesBeds, refusals, Optional::empty, moment -> true, shared));
        assertEquals(TaskResult.Status.DONE, first.status());
        assertTrue(shared.contains(BED_A), "睡不上的床写进跨任务黑名单");

        // 重插出来的新任务（全新实例）选床时，看到的排除名单里带着 BED_A。
        FakeScanner secondScanner = new FakeScanner();
        secondScanner.offer(new BedScanner.BedScan(
                List.of(new BedCandidate(BED_A, false, false, false, 4),
                        new BedCandidate(BED_B, false, false, false, 9)), true));
        NightRestTask second = nightRest(secondScanner, new StubApproaches(), new StubUsesBeds(),
                moment -> true, shared);
        TickContext context = tick();
        second.start(context);
        second.tick(context);
        assertTrue(secondScanner.askedExclusions.getFirst().contains(BED_A), "重插的任务继承试过的床");
    }
}
