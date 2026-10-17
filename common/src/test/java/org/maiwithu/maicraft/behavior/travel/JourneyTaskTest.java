// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.travel.spi.OfferSink;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.behavior.travel.spi.TravelMode;
import org.maiwithu.maicraft.behavior.travel.spi.TravelOffer;
import org.maiwithu.maicraft.behavior.travel.spi.TravelRequest;
import org.maiwithu.maicraft.behavior.travel.spi.TravelVia;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 换交通的一趟出行：近处走、远处比报价、走不通换方式、一段失败接着规划、指定方式如实结束、取消照样交代；
 * 坐着的一段与走路的一段被叫先到安全处再停时都转给它们。
 */
class JourneyTaskTest {

    private static final TravelVia ELEVATOR = new TravelVia("elevator", "坐电梯");

    private final TravelFakes.FakeWorld world = new TravelFakes.FakeWorld();
    private final TravelFakes.TestTick tick = new TravelFakes.TestTick();
    private final List<FakeWalk> walks = new ArrayList<>();
    /** 下一个走路段怎么收场；默认走到目的地。 */
    private Function<TravelDestination, FakeWalk> nextWalk = destination -> new FakeWalk(
            TaskResult.done("出行：到了"), destination.center());

    private JourneyTask journey(TravelDestination destination, String via, TravelMode... modes) {
        return new JourneyTask(new JourneyTask.Journey(destination, via, Map.of(), Permissions.DEFAULT, 0), world,
                List.of(modes), destination1 -> {
                    FakeWalk walk = nextWalk.apply(destination1);
                    walks.add(walk);
                    return walk;
                });
    }

    private TaskResult run(Task task, int maxTicks) {
        task.start(tick);
        for (int i = 0; i < maxTicks; i++) {
            if (task.tick(tick) instanceof TickResult.Finished finished) return finished.result();
            tick.nextTick();
        }
        throw new AssertionError("任务 " + maxTicks + " 刻内没有结束：" + task.describe());
    }

    private static TravelDestination at(int x, int y, int z) {
        return TravelDestination.confirmed(WorldPosition.here(x, y, z), 1);
    }

    private static JourneyDetails details(TaskResult result) {
        return assertInstanceOf(JourneyDetails.class, result.details());
    }

    @Test
    void 坐着的一段按要求停下后_站着等不再开下一段_放回来接着走() {
        // 坐电梯途中被叫暂停：这一段先停靠出轿厢，停下就站着等，不再问报价开下一段；放回来从站的地方接着规划。
        FakeMode elevator = new FakeMode(request -> new TravelOffer(ELEVATOR, request.from(), request.to(), 100,
                List.of(), List.of(), ""), WorldPosition.here(2, 100, 2));
        elevator.unsafe = true;
        JourneyTask task = journey(at(2, 100, 2), null, elevator);
        task.start(tick);
        for (int i = 0; i < 10 && elevator.started == 0; i++) {
            task.tick(tick);
            tick.nextTick();
        }
        assertEquals(1, elevator.started);

        assertTrue(task.stopSafely(), "坐着的一段要先停下");
        assertEquals(1, elevator.safeStops);
        assertFalse(task.stoppedSafely());
        elevator.failure = Problem.of(Problem.Kind.STUCK, "按要求先停下，停在半路");
        for (int i = 0; i < 5; i++) {
            task.tick(tick);
            tick.nextTick();
        }
        assertTrue(task.stoppedSafely(), "这一段停下了就是停稳了");
        int asked = elevator.asked;
        task.tick(tick);
        assertEquals(asked, elevator.asked, "收尾时不再问报价、不开下一段");

        task.pause();
        elevator.failure = null;
        TaskResult result = run(task, 50);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
    }

    @Test
    void 走路这一段悬在半空时_先停稳转给它_没落地整趟不报停稳() {
        // 走路途中被叫暂停：转给走路这一段的出行任务去落地，落地前整趟不报停稳；放回来接着走到，不另开一段。
        nextWalk = destination -> {
            FakeWalk walk = new FakeWalk(TaskResult.done("出行：到了"), destination.center());
            walk.airborne = true;
            return walk;
        };
        JourneyTask task = journey(at(10, 64, 0), null);
        task.start(tick);
        for (int i = 0; i < 10 && (walks.isEmpty() || walks.getFirst().ticks == 0); i++) {
            task.tick(tick);
            tick.nextTick();
        }
        FakeWalk walk = walks.getFirst();
        assertEquals(1, walk.ticks, "走路这一段走在半路");

        assertTrue(task.stopSafely(), "走路这一段悬在半空，要先落地");
        assertEquals(1, walk.safeStops, "走路这一段收到先停稳");
        assertFalse(task.stoppedSafely(), "还没落地，整趟没停稳");
        for (int i = 0; i < 3; i++) {
            task.tick(tick);
            tick.nextTick();
        }
        assertTrue(task.stoppedSafely(), "落地站稳，整趟停稳");

        task.pause();
        TaskResult result = run(task, 20);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(1, walks.size(), "接着走完这一段，不另开一段");
    }

    @Test
    void 近处直接走_不问报价() {
        FakeMode elevator = new FakeMode(request -> new TravelOffer(ELEVATOR, request.from(), request.to(), 1,
                List.of(), List.of(), ""), WorldPosition.here(10, 64, 0));
        TaskResult result = run(journey(at(10, 64, 0), null, elevator), 50);
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(0, elevator.asked);
        assertEquals(List.of("walk"), details(result).legs().stream().map(JourneyDetails.Leg::via).toList());
    }

    @Test
    void 远处有更快的电梯_坐电梯到了就结束() {
        FakeMode elevator = new FakeMode(request -> new TravelOffer(ELEVATOR, request.from(), request.to(), 100,
                List.of(), List.of(), "1F→5F"), WorldPosition.here(2, 100, 2));
        TaskResult result = run(journey(at(2, 100, 2), null, elevator), 50);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertTrue(walks.isEmpty(), "坐到了就不再走路");
        assertEquals(List.of("elevator"), details(result).legs().stream().map(JourneyDetails.Leg::via).toList());
        assertEquals(WorldPosition.here(2, 100, 2), details(result).arrivedAt());
    }

    @Test
    void 走不通就从这里比报价_换电梯() {
        nextWalk = destination -> new FakeWalk(TaskResult.failed("出行没有完成",
                Problem.of(Problem.Kind.UNREACHABLE, "试过的路线都不通")), null);
        FakeMode elevator = new FakeMode(request -> new TravelOffer(ELEVATOR, request.from(), request.to(), 400,
                List.of(), List.of(), ""), WorldPosition.here(3, 69, 0));
        TaskResult result = run(journey(at(3, 69, 0), null, elevator), 50);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(List.of("walk", "elevator"), details(result).legs().stream().map(JourneyDetails.Leg::via).toList());
        assertTrue(result.attempts().stream().anyMatch(attempt -> attempt.tried().equals("走路")));
    }

    @Test
    void 一段失败不判整趟失败_试过的报价不再试() {
        FakeMode elevator = new FakeMode(request -> new TravelOffer(ELEVATOR, request.from(), request.to(), 100,
                List.of(), List.of(), ""), null);
        elevator.failure = Problem.of(Problem.Kind.STUCK, "轿厢 10 秒没动");
        TaskResult result = run(journey(at(0, 120, 0), null, elevator), 50);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(1, elevator.started, "同一份报价这一趟只试一次");
        List<JourneyDetails.Leg> legs = details(result).legs();
        assertEquals(List.of("elevator", "walk"), legs.stream().map(JourneyDetails.Leg::via).toList());
        assertTrue(legs.get(0).result().contains("轿厢 10 秒没动"), legs.get(0).result());
    }

    @Test
    void 指定方式答不出_如实结束不改走路() {
        FakeMode elevator = new FakeMode(request -> null, null);
        elevator.decline = "附近没有通到那一层的电梯";
        TaskResult result = run(journey(at(0, 120, 0), "elevator", elevator), 50);
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.UNSUPPORTED, result.problem().kind());
        assertTrue(result.problem().message().contains("附近没有通到那一层的电梯"));
        assertTrue(walks.isEmpty());
    }

    @Test
    void 方式可以分几刻答完() {
        FakeMode elevator = new FakeMode(request -> new TravelOffer(ELEVATOR, request.from(), request.to(), 100,
                List.of(), List.of(), ""), WorldPosition.here(0, 120, 0));
        elevator.silentAsks = 3;
        TaskResult result = run(journey(at(0, 120, 0), null, elevator), 50);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(4, elevator.asked);
        assertEquals(1, elevator.started);
    }

    @Test
    void 只坐指定的方式不往别处去_送到就结束() {
        FakeMode elevator = new FakeMode(request -> new TravelOffer(ELEVATOR, request.from(), new BlockPos(0, 100, 0), 80,
                List.of(), List.of(), ""), WorldPosition.here(0, 100, 0));
        JourneyTask task = new JourneyTask(new JourneyTask.Journey(null, "elevator", Map.of("floor", "top"),
                Permissions.DEFAULT, 0), world, List.of(elevator), destination -> {
                    throw new AssertionError("只换楼层不走路");
                });
        TaskResult result = run(task, 50);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(WorldPosition.here(0, 100, 0), details(result).arrivedAt());
        assertEquals("top", elevator.lastRequest.options().get("floor"));
    }

    @Test
    void 有方式说这一段非它不可_近处也只问它() {
        // 站在船上要先下船：近处也不走路，只问说"非我不可"的那种方式，用它的报价。
        FakeMode deck = new FakeMode(request -> new TravelOffer(ELEVATOR, request.from(), new BlockPos(3, 64, 0), 40,
                List.of(), List.of(), ""), WorldPosition.here(3, 64, 0));
        deck.claims = true;
        TaskResult result = run(journey(at(3, 64, 0), null, deck), 50);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertTrue(walks.isEmpty(), "非它不可时不先走路");
        assertEquals(1, deck.started);
    }

    @Test
    void 走路途中被取消_出行任务的事实照样记进来() {
        nextWalk = destination -> new FakeWalk(null, null);
        JourneyTask task = journey(at(10, 64, 0), null);
        task.start(tick);
        for (int i = 0; i < 5; i++) {
            task.tick(tick);
            tick.nextTick();
        }
        TaskResult result = task.close(CloseReason.CANCELLED);
        assertEquals(TaskResult.Status.CANCELLED, result.status());
        assertEquals(CloseReason.CANCELLED, walks.get(0).closedWith);
        assertEquals(1, result.changes().size(), "垫下的那一格记在整趟里");
        assertEquals(List.of("walk"), details(result).legs().stream().map(JourneyDetails.Leg::via).toList());
    }

    /** 走路一段的替身：推进两刻后按给定结果收场（null 表示一直走），收场时把角色挪到给定位置。 */
    private final class FakeWalk implements Task {
        private final TaskResult outcome;
        private final BlockPos landing;
        private int ticks;
        CloseReason closedWith;
        /** 走在半路悬着：叫它先停稳要再推进两刻才落地，落地后站着等。 */
        boolean airborne;
        int safeStops;
        /** 叫停之后推进了几刻；没在收尾为 -1。 */
        private int stopTicks = -1;

        FakeWalk(TaskResult outcome, BlockPos landing) {
            this.outcome = outcome;
            this.landing = landing;
        }

        @Override public TickResult tick(TickContext context) {
            if (stopTicks >= 0) {
                stopTicks++;
                return TickResult.RUNNING;
            }
            if (++ticks < 2 || outcome == null) return TickResult.RUNNING;
            if (landing != null) world.spot = WorldPosition.here(landing.getX(), landing.getY(), landing.getZ());
            return TickResult.finished(outcome);
        }

        @Override public boolean stopSafely() {
            if (!airborne) return false;
            safeStops++;
            stopTicks = 0;
            return true;
        }

        @Override public boolean stoppedSafely() {
            return stopTicks < 0 || stopTicks >= 2;
        }

        @Override public void pause() {
            stopTicks = -1;
        }

        @Override public TaskResult close(CloseReason reason) {
            closedWith = reason;
            return outcome != null ? outcome : TaskResult.builder(TaskResult.Status.CANCELLED, "出行：被取消")
                    .change(new Change(Change.Kind.BLOCK_PLACED, "1, 64, 0", 1, "垫路")).build();
        }

        @Override public String describe() {
            return "走路替身";
        }
    }

    /** 出行方式替身：前几次不答，之后给报价或说送不了；一段推进两刻后送到给定位置，或按给定问题失败。 */
    private final class FakeMode implements TravelMode {
        private final Function<TravelRequest, TravelOffer> answer;
        private final WorldPosition dropOff;
        int silentAsks;
        String decline;
        Problem failure;
        int asked;
        int started;
        TravelRequest lastRequest;
        boolean claims;
        int safeStops;
        /** 这一段坐着时停下不安全（在轿厢里）。 */
        boolean unsafe;

        @Override public boolean claims(TravelRequest request) {
            return claims && started == 0;
        }

        FakeMode(Function<TravelRequest, TravelOffer> answer, WorldPosition dropOff) {
            this.answer = answer;
            this.dropOff = dropOff;
        }

        @Override public TravelVia via() {
            return ELEVATOR;
        }

        @Override public void offer(TravelRequest request, OfferSink sink) {
            asked++;
            lastRequest = request;
            if (asked <= silentAsks) return;
            if (decline != null) {
                sink.decline(Problem.Kind.UNSUPPORTED, decline);
                return;
            }
            sink.offer(answer.apply(request));
        }

        @Override public TravelLeg start(TravelRequest request, TravelOffer offer) {
            started++;
            return new TravelLeg() {
                private int ticks;

                @Override public ActionStatus tick(TickContext context) {
                    if (++ticks < 2) return ActionStatus.progressed();
                    if (failure != null) return ActionStatus.failed(failure);
                    if (dropOff != null) world.spot = dropOff;
                    return ActionStatus.done();
                }

                @Override public void requestSafeStop() {
                    safeStops++;
                }

                @Override public Interruptibility interruptibility() {
                    return unsafe ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
                }

                @Override public String describe() {
                    return "坐电梯替身";
                }
            };
        }
    }
}
