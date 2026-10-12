// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.combat.Battle;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跟随任务：迟滞起步与停下、走着换目标点、近了不跑；跟不过去先等再试；跟丢按原因找；
 * 停着看他、挡道就让；同乘与下车；帮打；陪着的人睡没睡。
 */
class FollowTaskTest {

    private static final Target SEEN = new Target.Seen("e1");
    private static final Permissions NO_TERRAIN = new Permissions(
            Permissions.BlockChanges.NONE, Permissions.Fight.HOSTILE_MOBS, false,
            Permissions.AnimalKilling.WILD, Permissions.SurvivalNeeds.ON, Set.of());
    private static final Permissions SELF_DEFENSE_ONLY = new Permissions(
            Permissions.BlockChanges.NATURAL, Permissions.Fight.SELF_DEFENSE, false,
            Permissions.AnimalKilling.WILD, Permissions.SurvivalNeeds.ON, Set.of());

    /** 刻号替身。 */
    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            throw new IllegalStateException("跟随任务的离线测试不碰角色对象");
        }
    }

    /** 他此刻样子的拼法：默认站在 (10, 64, 10) 的地上，我在他东边 gap 格。 */
    private static final class Him {
        BlockPos ground = new BlockPos(10, 64, 10);
        double gap = 3;
        boolean sleeping;
        boolean dead;
        String portal;
        String dimension = "minecraft:overworld";
        Integer vehicle;
        boolean sameVehicle;
        boolean meRiding;
        boolean inFight;
        Vec3 me;

        FollowView.Sighting sighting() {
            Vec3 feet = Vec3.atBottomCenterOf(ground);
            Vec3 mine = me != null ? me : feet.add(gap, 0, 0);
            return new FollowView.Sighting(new WorldPosition(ground.getX(), ground.getY(), ground.getZ(), dimension),
                    ground, feet, feet.add(0, 1.62, 0), 0f, "西边", gap, gap, mine, sleeping, dead, portal,
                    vehicle, vehicle == null ? null : "minecraft:oak_boat", sameVehicle, meRiding, inFight);
        }
    }

    /** 目标视图替身：锁定与观察按字段回答；visible 为假时看不见。 */
    private static final class FakeView implements FollowView {
        final Him him = new Him();
        boolean visible = true;
        Presence presence = Presence.ONLINE;
        Optional<BlockPos> aside = Optional.empty();
        TickContext lockedWith;
        boolean lockFails;

        @Override public Locked lock(TickContext context, Target target) {
            lockedWith = context;
            return lockFails ? null : new Locked(7, "minecraft:player", "Steve",
                    new WorldPosition(10, 64, 10, "minecraft:overworld"), "西边");
        }

        @Override public Sighting observe(TickContext context) {
            return visible ? him.sighting() : null;
        }

        @Override public Presence presence(TickContext context) {
            return presence;
        }

        @Override public Optional<BlockPos> asideSpot(TickContext context, Sighting him, double headingX,
                                                      double headingZ, int distance) {
            return aside;
        }
    }

    /** 一次走到替身：按住状态答话，记下叫停、换目标、限跑与收尾。 */
    private static final class FakeWalk implements WalkRun {
        final GoalCompiler.Compiled target;
        WalkReport.State state = WalkReport.State.ON_THE_WAY;
        boolean fail;
        boolean stopRequested;
        boolean closed;
        int retargets;
        boolean sprint = true;

        FakeWalk(GoalCompiler.Compiled target) {
            this.target = target;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (fail) {
                state = WalkReport.State.FAILED;
                return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "前面是一道 6 格高的坎"));
            }
            if (stopRequested) {
                state = WalkReport.State.STOPPED;
                return ActionStatus.done();
            }
            if (state == WalkReport.State.ARRIVED) return ActionStatus.done();
            return ActionStatus.running();
        }

        @Override public WalkReport report() {
            return switch (state) {
                case PLANNING -> WalkReport.planning();
                case ON_THE_WAY -> WalkReport.onTheWay(BlockPos.ZERO, false);
                case ARRIVED -> WalkReport.arrived(BlockPos.ZERO, false);
                case STOPPED -> WalkReport.stopped(BlockPos.ZERO, false);
                case FAILED -> WalkReport.failed(Problem.of(Problem.Kind.UNREACHABLE, "过不去"), null, false);
            };
        }

        @Override public boolean stop() {
            stopRequested = true;
            return false;
        }

        @Override public boolean retarget(GoalCompiler.Compiled next) {
            retargets++;
            return true;
        }

        @Override public void allowSprint(boolean allowed) {
            sprint = allowed;
        }

        @Override public void close() {
            closed = true;
        }

        @Override public String describe() {
            return "走着";
        }
    }

    /** 走到替身：每次开走记下目标与许可；failNext 让下一趟一推进就走不通。 */
    private static final class FakeWalks implements WalkTo {
        final List<FakeWalk> runs = new ArrayList<>();
        final List<TerrainPermit> permits = new ArrayList<>();
        boolean failNext;

        @Override public WalkRun start(GoalCompiler.Compiled target, TerrainPermit permit) {
            FakeWalk walk = new FakeWalk(target);
            walk.fail = failNext;
            failNext = false;
            runs.add(walk);
            permits.add(permit);
            return walk;
        }

        FakeWalk last() {
            return runs.get(runs.size() - 1);
        }
    }

    /** 身体动作替身：记下看向、上车、下车、过门与帮打。 */
    private static final class FakeMoves implements FollowMoves {
        final List<Vec3> looks = new ArrayList<>();
        Action ride;
        String portalTarget;
        Task portalTask;
        Battle.State fightEnds = Battle.State.FINISHED;
        int fightTicks;
        int helps;

        @Override public void lookAt(TickContext context, Vec3 point) {
            looks.add(point);
        }

        @Override public Optional<Action> rideAlong(TickContext context, int vehicleId, Permissions permissions) {
            return Optional.ofNullable(ride);
        }

        @Override public Action getOff() {
            return new Instant();
        }

        @Override public Task crossPortal(String targetDimension, Permissions permissions) {
            portalTarget = targetDimension;
            return portalTask;
        }

        @Override public HelpFight helpFight(Supplier<Vec3> leader) {
            helps++;
            return new HelpFight() {
                int ticks;

                @Override public Battle.Moment tick(TickContext context) {
                    ticks++;
                    Battle.State state = ticks > fightTicks ? fightEnds : Battle.State.FIGHTING;
                    return new Battle.Moment(state, "打僵尸", true, true, false, 3, "血见底", false);
                }

                @Override public boolean retreating() { return false; }
                @Override public Interruptibility interruptibility() { return Interruptibility.WORKING; }
                @Override public void pause() {}
                @Override public void close() {}
                @Override public String describe() { return "打僵尸"; }
            };
        }
    }

    /** 一推进就做完的动作。 */
    private static final class Instant implements Action {
        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.done();
        }

        @Override public String describe() {
            return "一下就好";
        }
    }

    /** 一推进就做完的子任务：过门成功。 */
    private static final class CrossedTask implements Task {
        @Override public TickResult tick(TickContext context) {
            return TickResult.finished(TaskResult.done("到了下界"));
        }

        @Override public void pause() {}

        @Override public TaskResult close(CloseReason reason) {
            return TaskResult.done("到了下界");
        }

        @Override public String describe() {
            return "过门";
        }
    }

    /** 记下发出的事件。 */
    private static final class Events implements TaskEventSink {
        final List<String> follow = new ArrayList<>();

        @Override public void publish(TaskEvent.Kind kind, String message) {
            if (kind == TaskEvent.Kind.FOLLOW_STATUS) follow.add(message);
        }

        boolean said(String words) {
            return follow.stream().anyMatch(message -> message.contains(words));
        }
    }

    private final FakeView view = new FakeView();
    private final FakeWalks walks = new FakeWalks();
    private final FakeMoves moves = new FakeMoves();
    private final Events events = new Events();
    private long clock;

    private FollowTask task(int distance, Permissions permissions) {
        FollowTask follow = new FollowTask(new FollowInput(SEEN, distance, permissions), view, moves, walks, events);
        follow.start(new Tick(clock));
        return follow;
    }

    private TickResult step(FollowTask follow) {
        return follow.tick(new Tick(clock++));
    }

    private TickResult run(FollowTask follow, int ticks) {
        TickResult result = TickResult.RUNNING;
        for (int i = 0; i < ticks && result instanceof TickResult.Running; i++) {
            result = step(follow);
        }
        return result;
    }

    @Test
    void startsOnlyBeyondDistancePlusTwo() {
        // 离他脚下 4 格（设定 3，+2 以内）不起步，看着他；走到 6 格起步。
        view.him.gap = 4;
        FollowTask follow = task(3, Permissions.DEFAULT);
        run(follow, 3);
        assertTrue(walks.runs.isEmpty(), "迟滞以内不起步");
        assertFalse(moves.looks.isEmpty(), "停着时看着他");

        view.him.gap = 6;
        step(follow);
        assertEquals(1, walks.runs.size(), "超出 +2 才起步");
    }

    @Test
    void stopsOnceWithinDistanceInsteadOfWalkingIntoHim() {
        view.him.gap = 8;
        FollowTask follow = task(3, Permissions.DEFAULT);
        run(follow, 2);
        FakeWalk walk = walks.last();
        assertTrue(walk.sprint, "离得远：跑");
        view.him.gap = 5;
        step(follow);
        assertFalse(walk.sprint, "进 distance+4 以内只走不跑");
        view.him.gap = 2.8;
        step(follow);
        assertTrue(walk.stopRequested, "进了设定距离就叫停，不走到他跟前");
    }

    @Test
    void retargetsWhileHeKeepsWalking() {
        view.him.gap = 10;
        FollowTask follow = task(3, Permissions.DEFAULT);
        run(follow, 2);
        FakeWalk walk = walks.last();
        // 他挪了 2 格：不换；挪出 3 格：换目标点，不另开一趟。
        view.him.ground = new BlockPos(12, 64, 10);
        step(follow);
        assertEquals(0, walk.retargets);
        view.him.ground = new BlockPos(14, 64, 10);
        step(follow);
        assertEquals(1, walk.retargets, "走着换目标点");
        assertEquals(1, walks.runs.size(), "路线不停下重算");
    }

    @Test
    void blockedWaitsRetriesWhenHeMovesAndGivesUpAfterTwoMinutes() {
        view.him.gap = 10;
        walks.failNext = true;
        FollowTask follow = task(3, NO_TERRAIN);
        run(follow, 3);
        assertTrue(events.said("跟不过去"), "跟不过去先说一声");
        assertTrue(events.said("change_blocks"), "说清放开哪项许可也许能过去");
        assertEquals(TerrainPermit.WALK_ONLY, walks.permits.get(0), "这次许可不动地形：走路也只走不改");
        int tries = walks.runs.size();

        // 他没挪：不再试；他挪开 3 格、过了 3 秒：再试一次。
        run(follow, 80);
        assertEquals(tries, walks.runs.size(), "他没挪就不再试");
        walks.failNext = true;
        view.him.ground = new BlockPos(10, 64, 15);
        run(follow, 2);
        assertEquals(tries + 1, walks.runs.size(), "他挪开了：再试一次");

        TickResult result = run(follow, (int) FollowTask.BLOCKED_LIMIT_TICKS);
        TaskResult finished = assertInstanceOf(TickResult.Finished.class, result).result();
        assertEquals(TaskResult.Status.FAILED, finished.status());
        assertEquals(Problem.Kind.UNREACHABLE, finished.problem().kind());
        assertTrue(finished.problem().suggestion().contains("change_blocks"));
    }

    @Test
    void blockedResumesWhenHeComesBack() {
        view.him.gap = 10;
        walks.failNext = true;
        FollowTask follow = task(3, Permissions.DEFAULT);
        run(follow, 3);
        view.him.gap = 4;
        TickResult result = run(follow, 3);
        assertInstanceOf(TickResult.Running.class, result);
        assertTrue(events.said("回到我身边"), "他回来了就接着跟");
    }

    @Test
    void retryThatGetsGoingKeepsTheSameWalkIntoFollowing() {
        // 再试的这一趟走起来了：转回跟着时同一趟路接着走，不因换阶段被收掉。
        view.him.gap = 10;
        walks.failNext = true;
        FollowTask follow = task(3, Permissions.DEFAULT);
        run(follow, 3);
        view.him.ground = new BlockPos(10, 64, 20);
        clock += FollowTask.RETRY_GAP_TICKS;
        run(follow, 3);
        FakeWalk retry = walks.last();
        assertFalse(events.said("又跟上了"), "路走起来了还没到他跟前：不说跟上了");
        assertFalse(retry.closed, "换阶段不收掉正在走的路");
        step(follow);
        assertFalse(retry.closed);
        view.him.gap = 4;
        step(follow);
        assertTrue(events.said("又跟上了"), "真到他跟前了才说跟上了");
    }

    @Test
    void lostOutOfSightGoesToLastSpotAndComesBackWhenSeen() {
        view.him.gap = 4;
        FollowTask follow = task(3, Permissions.DEFAULT);
        run(follow, 2);
        view.visible = false;
        step(follow);
        assertTrue(events.said("跟丢了"), "跟丢了说一声");
        assertEquals(1, walks.runs.size(), "去最后看见他的地方");
        view.visible = true;
        step(follow);
        assertTrue(events.said("又看见他了"));
    }

    @Test
    void lostOutOfSightGivesUpAfterThirtySeconds() {
        view.him.gap = 4;
        FollowTask follow = task(3, Permissions.DEFAULT);
        run(follow, 2);
        view.visible = false;
        TickResult result = run(follow, (int) FollowTask.SEARCH_LIMIT_TICKS + 5);
        TaskResult finished = assertInstanceOf(TickResult.Finished.class, result).result();
        assertEquals(Problem.Kind.TARGET_GONE, finished.problem().kind());
        assertTrue(finished.problem().message().contains("西边"), "写明最后看到的方位");
    }

    @Test
    void offlineAndDeadAreToldApart() {
        view.him.gap = 4;
        FollowTask follow = task(3, Permissions.DEFAULT);
        run(follow, 2);
        view.visible = false;
        view.presence = FollowView.Presence.OFFLINE;
        TickResult result = run(follow, (int) FollowTask.SEARCH_LIMIT_TICKS + 5);
        assertTrue(events.said("下线"));
        assertTrue(assertInstanceOf(TickResult.Finished.class, result).result().problem().message().contains("下线"));
        assertEquals(0, walks.runs.size(), "下线了原地等，不跑去找");

        FakeView other = new FakeView();
        Events heard = new Events();
        FollowTask second = new FollowTask(new FollowInput(SEEN, 3, Permissions.DEFAULT), other, moves, walks, heard);
        second.start(new Tick(0));
        second.tick(new Tick(0));
        other.him.dead = true;
        second.tick(new Tick(1));
        assertTrue(heard.said("死了"));
    }

    @Test
    void followsThroughANetherPortal() {
        view.him.gap = 4;
        moves.portalTask = new CrossedTask();
        FollowTask follow = task(3, Permissions.DEFAULT);
        run(follow, 2);
        view.him.portal = "minecraft:nether_portal";
        step(follow);
        view.visible = false;
        run(follow, 3);
        assertEquals("minecraft:the_nether", moves.portalTarget, "跟着走进下界门");
        assertTrue(events.said("跟着过了传送门"));
        view.visible = true;
        step(follow);
        assertTrue(events.said("又看见他了"));
    }

    @Test
    void stepsAsideWhenInHisWay() {
        // 他朝我走过来：让到旁边那一格去。
        view.him.gap = 3;
        view.aside = Optional.of(new BlockPos(10, 64, 13));
        FollowTask follow = task(3, Permissions.DEFAULT);
        run(follow, 2);
        view.him.me = new Vec3(13.5, 64, 10.5);
        view.him.ground = new BlockPos(11, 64, 10);
        step(follow);
        view.him.ground = new BlockPos(12, 64, 10);
        step(follow);
        assertEquals(1, walks.runs.size(), "让路走一趟");
        assertFalse(walks.last().sprint, "让路不跑");
    }

    @Test
    void ridesTheSameBoatAndGetsOffWithHim() {
        view.him.gap = 4;
        moves.ride = new Instant();
        FollowTask follow = task(3, Permissions.DEFAULT);
        run(follow, 2);
        view.him.vehicle = 5;
        run(follow, 2);
        assertTrue(events.said("坐上了他那辆"), "坐上同一条船");
        view.him.sameVehicle = true;
        view.him.meRiding = true;
        run(follow, 3);
        view.him.vehicle = null;
        view.him.sameVehicle = false;
        step(follow);
        assertTrue(events.said("我也下来"));
        view.him.meRiding = false;
        TickResult result = run(follow, 3);
        assertInstanceOf(TickResult.Running.class, result, "下来接着跟");
    }

    @Test
    void noSeatMeansWalkingAlongAndSayingSoOnce() {
        view.him.gap = 4;
        view.him.vehicle = 9;
        FollowTask follow = task(3, Permissions.DEFAULT);
        run(follow, 5);
        assertEquals(1, events.follow.stream().filter(message -> message.contains("没有我的座")).count());
    }

    @Test
    void helpsWhenHeFightsUnlessOnlySelfDefense() {
        view.him.gap = 4;
        view.him.inFight = true;
        moves.fightTicks = 2;
        FollowTask follow = task(3, Permissions.DEFAULT);
        run(follow, 3);
        assertEquals(1, moves.helps, "他在打：上去帮");
        assertTrue(follow.confidentAgainstCurrentThreats(), "正在帮打：自卫让位");
        view.him.inFight = false;
        run(follow, 5);
        assertTrue(events.said("帮他打完了"));

        FakeMoves calm = new FakeMoves();
        FakeView fighting = new FakeView();
        fighting.him.inFight = true;
        FollowTask defensive = new FollowTask(new FollowInput(SEEN, 3, SELF_DEFENSE_ONLY), fighting, calm, walks, events);
        defensive.start(new Tick(0));
        for (int i = 0; i < 5; i++) defensive.tick(new Tick(i));
        assertEquals(0, calm.helps, "fight=self_defense 时不帮");
    }

    @Test
    void companionAsleepFollowsWhatIsSeen() {
        view.him.gap = 3;
        FollowTask follow = task(3, Permissions.DEFAULT);
        run(follow, 2);
        assertFalse(follow.companionAsleep());
        view.him.sleeping = true;
        step(follow);
        assertTrue(follow.companionAsleep(), "他躺下了");
    }

    @Test
    void lockFailureIsNotFound() {
        view.lockFails = true;
        FollowTask follow = task(3, Permissions.DEFAULT);
        TaskResult finished = assertInstanceOf(TickResult.Finished.class, step(follow)).result();
        assertEquals(Problem.Kind.NOT_FOUND, finished.problem().kind());
    }

    @Test
    void lockingSeesTheFirstTickContext() {
        // 第一刻就要锁定目标：生产实现读世界离不开当刻的上下文，不能交给它一个空的。
        FollowTask follow = task(3, Permissions.DEFAULT);
        Tick first = new Tick(clock);
        follow.tick(first);
        assertSame(first, view.lockedWith);
    }

    @Test
    void cancellingClosesTheWalkInHand() {
        view.him.gap = 10;
        FollowTask follow = task(3, Permissions.DEFAULT);
        run(follow, 2);
        FakeWalk walk = walks.last();
        assertNotNull(walk);
        follow.close(CloseReason.CANCELLED);
        assertTrue(walk.closed, "取消时把手上的路结清");
    }
}
