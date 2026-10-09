// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.maiwithu.maicraft.game.player.PlayerContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 跟随任务：迟滞起步与停止、目标消失按 TARGET_GONE 结束、路线走不通附开路说明。 */
class FollowTaskTest {

    private static final Target SEEN = new Target.Seen("e1");

    /** 刻号替身。 */
    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            throw new IllegalStateException("跟随任务的离线测试不碰角色对象");
        }
    }

    /** 目标视图替身：锁定按脚本回答，观察按脚本逐刻给距离；脚本用完重复最后一份，gone 后看不见。 */
    private static final class FakeView implements FollowView {
        private Locked locked;
        private Observed current;
        private final List<Observed> script = new ArrayList<>();

        @Override
        public Locked lock(TickContext context, Target target) {
            return locked;
        }

        @Override
        public Observed observe(TickContext context) {
            if (!script.isEmpty()) {
                current = script.remove(0);
            }
            return current;
        }

        FakeView at(double distance) {
            locked = new Locked(7, "minecraft:player_like", new WorldPosition(10, 64, 10, "minecraft:overworld"), "北");
            script.add(new Observed(new WorldPosition(10, 64, 10, "minecraft:overworld"), "北", distance));
            return this;
        }

        FakeView gone() {
            script.clear();
            current = null;
            return this;
        }
    }

    /** 走到替身：记录每次开走与许可；推进一次即报到达。 */
    private static final class FakeWalks implements WalkTo {
        final List<Double> startedRadii = new ArrayList<>();
        final List<TerrainPermit> permits = new ArrayList<>();
        boolean failNext;

        @Override
        public WalkRun start(GoalCompiler.Compiled target, TerrainPermit permit) {
            startedRadii.add(1.0);
            permits.add(permit);
            boolean fail = failNext;
            failNext = false;
            return new FakeWalk(fail);
        }
    }

    /** 一次走到：第一刻报失败或到达。 */
    private record FakeWalk(boolean fail) implements WalkRun {
        @Override
        public ActionStatus tick(TickContext context) {
            if (fail) {
                return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "过不去"));
            }
            return ActionStatus.done();
        }

        @Override
        public WalkReport report() {
            return fail ? WalkReport.failed(Problem.of(Problem.Kind.UNREACHABLE, "过不去"), null, false)
                    : WalkReport.arrived(null, false);
        }

        @Override
        public boolean stop() {
            return true;
        }

        @Override
        public String describe() {
            return "走着";
        }
    }

    private static FollowTask task(FollowView view, FakeWalks walks, int distance) {
        return new FollowTask(new FollowInput(SEEN, distance, Permissions.DEFAULT), view, walks);
    }

    @Test
    void hysteresisStartsOnlyBeyondPlusTwo() {
        // 距离 4（设定 3，+2 以内）不起步；走到 6 起步。
        FakeWalks walks = new FakeWalks();
        FakeView view = new FakeView().at(4.0);
        FollowTask follow = task(view, walks, 3);
        follow.start(new Tick(0));
        assertTrue(follow.tick(new Tick(0)) instanceof TickResult.Running);
        assertTrue(walks.startedRadii.isEmpty(), "迟滞以内不起步");

        view.at(6.0);
        follow.tick(new Tick(1));
        follow.tick(new Tick(2));
        assertEquals(1, walks.startedRadii.size(), "超出 +2 才起步");
    }

    @Test
    void goingAwayThenBackDoesNotStepStop() {
        // 起步后回到设定值内：停（不再开新走到），不反复。
        FakeWalks walks = new FakeWalks();
        FakeView view = new FakeView().at(6.0).at(2.5);
        FollowTask follow = task(view, walks, 3);
        follow.start(new Tick(0));
        follow.tick(new Tick(0));
        follow.tick(new Tick(1));
        assertEquals(1, walks.startedRadii.size(), "回到设定值内就停，不再开走");
    }

    @Test
    void targetGoneEndsWithDirection() {
        FakeView view = new FakeView().at(2.0).gone();
        // 观察合同：编号被重用给别的东西时 observe 也返回 null——任务只认"看不见"。
        FollowTask follow = task(view, new FakeWalks(), 3);
        follow.start(new Tick(0));
        follow.tick(new Tick(0));
        follow.tick(new Tick(1));
        TickResult result = follow.tick(new Tick(2));
        TaskResult finished = assertInstanceOf(TickResult.Finished.class, result).result();
        assertEquals(TaskResult.Status.FAILED, finished.status());
        assertEquals(Problem.Kind.TARGET_GONE, finished.problem().kind());
        assertTrue(finished.problem().message().contains("北"), "写明最后看到的方位");
    }

    @Test
    void unreachableExplainsHowToOpenPath() {
        FakeWalks walks = new FakeWalks();
        walks.failNext = true;
        FakeView view = new FakeView().at(6.0);
        // 一格都不许动：走不过去时结果里要写清放开哪项许可也许就能过去。
        FollowTask follow = new FollowTask(
                new FollowInput(SEEN, 3, new Permissions(
                        Permissions.BlockChanges.NONE, Permissions.Fight.HOSTILE_MOBS, false,
                        Permissions.AnimalKilling.WILD, Permissions.SurvivalNeeds.ON, Set.of())),
                view, walks);
        follow.start(new Tick(0));
        follow.tick(new Tick(0));
        follow.tick(new Tick(1));
        TickResult result = follow.tick(new Tick(2));
        TaskResult finished = assertInstanceOf(TickResult.Finished.class, result).result();
        assertEquals(TaskResult.Status.FAILED, finished.status());
        assertEquals(Problem.Kind.UNREACHABLE, finished.problem().kind());
        assertTrue(finished.problem().suggestion().contains("change_blocks"), "附上开路要放开的许可");
        // 默认许可不改地形：走到只走不改。
        assertEquals(TerrainPermit.WALK_ONLY, walks.permits.get(0));
    }
}
