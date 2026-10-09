// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.HashMap;
import java.util.Map;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.game.player.PlayerContext;

/** 出行的离线测试替身：摆现场、演走到，不碰游戏对象。 */
final class TravelFakes {

    private TravelFakes() {}

    /** 本刻上下文替身：只有刻号。 */
    static final class TestTick implements TickContext {
        private long gameTick = 0;

        @Override public long gameTick() {
            return gameTick;
        }

        @Override public PlayerContext player() {
            throw new IllegalStateException("出行测试不应该碰到角色对象");
        }

        void nextTick() {
            gameTick++;
        }
    }

    /** 现场替身：角色站哪、朝哪。 */
    static final class FakeWorld implements TravelWorldView {
        private WorldPosition spot = WorldPosition.here(0, 64, 0);
        Target.Toward facing = Target.Toward.NORTH;

        @Override public WorldPosition currentSpot() {
            return spot;
        }

        @Override public Target.Toward currentFacing() {
            return facing;
        }
    }

    /** 记过的地点替身：手工登记。 */
    static final class FakePlaces implements ReadsRememberedPlaces {
        private final Map<String, WorldPosition> places = new HashMap<>();

        void put(String name, WorldPosition position) {
            places.put(name, position);
        }

        @Override public Optional<WorldPosition> place(String name) {
            return Optional.ofNullable(places.get(name));
        }
    }

    /** 观察编号替身：手工登记。 */
    static final class FakeSeen implements ReadsSeenTargets {
        private final Map<String, WorldPosition> seen = new HashMap<>();

        void put(String id, WorldPosition position) {
            seen.put(id, position);
        }

        @Override public Optional<WorldPosition> positionOf(String observationId) {
            return Optional.ofNullable(seen.get(observationId));
        }
    }

    /** 垫过的方块替身：结算时照实交出。 */
    static final class FakePlaced implements ReadsPlacedBlocks {
        private final List<BlockPos> placed = new ArrayList<>();

        void put(BlockPos cell) {
            placed.add(cell);
        }

        @Override public List<BlockPos> placedDuringCurrentWalk() {
            return List.copyOf(placed);
        }
    }

    /** 进展记录替身：把逐刻报告收下来。 */
    static final class ProgressRecorder implements TravelProgressListener {
        final List<TravelProgress> received = new ArrayList<>();

        @Override public void onTravelProgress(TravelProgress progress) {
            received.add(progress);
        }
    }

    /**
     * 走到替身：按脚本逐刻交出走到情况；记下收到的导航目标与许可。
     * stop 脚本控制"先请求、落到安全边界才停"要演几刻。
     */
    static final class FakeWalks implements WalkTo {
        final List<GoalCompiler.Compiled> started = new ArrayList<>();
        final List<TerrainPermit> permits = new ArrayList<>();
        private final Deque<ScriptedWalk> walks = new ArrayDeque<>();
        private ScriptedWalk current;

        /** 登记一段走到运行；每次 start 依次取用，取完后复用最后一段。 */
        void enqueue(ScriptedWalk walk) {
            walks.add(walk);
        }

        @Override public WalkRun start(GoalCompiler.Compiled target, TerrainPermit permit) {
            started.add(target);
            permits.add(permit);
            if (!walks.isEmpty()) {
                current = walks.remove();
            }
            return current;
        }
    }

    /** 一段演出来的走到运行。 */
    static final class ScriptedWalk implements WalkRun {
        private final Deque<WalkReport> reports = new ArrayDeque<>();
        private final Deque<Boolean> stopAnswers = new ArrayDeque<>();
        private WalkReport last = WalkReport.planning();
        int stopRequests;
        int pauseRequests;

        /** 被生存需求打断：只记请求；停下的过程由测试用报告脚本演出来。 */
        @Override public void pause() {
            pauseRequests++;
        }

        ScriptedWalk(WalkReport... reports) {
            for (WalkReport report : reports) {
                this.reports.add(report);
            }
        }

        /** stop() 的回答队列：false 表示"接受了请求还没停稳"，true 表示本刻已停住。 */
        ScriptedWalk stopAnswers(Boolean... answers) {
            for (Boolean answer : answers) {
                stopAnswers.add(answer);
            }
            return this;
        }

        /** 测试中途改剧本：清掉没演的脚本换成新的。 */
        void rerun(WalkReport... reports) {
            this.reports.clear();
            for (WalkReport report : reports) {
                this.reports.add(report);
            }
        }

        @Override public ActionStatus tick(TickContext context) {
            if (reports.isEmpty()) {
                return ActionStatus.running();
            }
            WalkReport next = reports.remove();
            last = next;
            return switch (next.state()) {
                case ARRIVED, STOPPED -> ActionStatus.done();
                case FAILED -> ActionStatus.failed(next.problem());
                default -> ActionStatus.progressed();
            };
        }

        @Override public WalkReport report() {
            return last;
        }

        @Override public boolean stop() {
            stopRequests++;
            Boolean answer = stopAnswers.poll();
            return answer == null || answer;
        }

        @Override public String describe() {
            return "演出来的走到";
        }
    }

    /** 给断言用的快捷取值：编好的导航目标。 */
    static NavGoal goalOf(GoalCompiler.Compiled compiled) {
        return compiled.goal();
    }

    /** 提问原因快捷判断。 */
    static Question.Reason reasonOf(Question question) {
        return question.reason();
    }

    /** 问题种类快捷判断。 */
    static Problem.Kind kindOf(Problem problem) {
        return problem.kind();
    }
}
