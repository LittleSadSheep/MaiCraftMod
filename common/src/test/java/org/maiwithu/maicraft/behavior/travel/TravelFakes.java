// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.perception.SceneSign;
import org.maiwithu.maicraft.behavior.perception.SignLines;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerInput;
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
import java.util.OptionalInt;
import java.util.Optional;
import java.util.HashMap;
import java.util.Map;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.game.player.PlayerContext;

/** 出行的离线测试替身：摆现场、演走到，不碰游戏对象。 */
final class TravelFakes {

    private TravelFakes() {}

    /** 本刻上下文替身：只有刻号，角色可换（默认没有角色——出行测试多半不碰角色对象）。 */
    static final class TestTick implements TickContext {
        private long gameTick = 0;
        private PlayerContext player;

        @Override public long gameTick() {
            return gameTick;
        }

        @Override public PlayerContext player() {
            return player;
        }

        void nextTick() {
            gameTick++;
        }

        /** 给这一刻摆上角色（出行记账要看背包）。 */
        void withPlayer(PlayerContext player) {
            this.player = player;
        }
    }

    /** 角色替身：只有一个背包视图，其余一概不接（出行任务只读背包记账）。 */
    static final class FakePlayer implements PlayerContext {
        private final BackpackView backpack;

        FakePlayer(BackpackView backpack) {
            this.backpack = backpack;
        }

        @Override public BackpackView backpack() {
            return backpack;
        }

        @Override public LocalPlayer localPlayer() { return null; }
        @Override public ClientLevel level() { return null; }
        @Override public ClientPacketListener connection() { return null; }
        @Override public PlayerInput input() {
            throw new UnsupportedOperationException("出行测试不碰角色输入");
        }
        @Override public InteractionSender interactionSender() {
            throw new UnsupportedOperationException("出行测试不碰交互");
        }
        @Override public MenuActions menuActions() {
            throw new UnsupportedOperationException("出行测试不碰界面");
        }
        @Override public long clientTick() { return 0; }
        @Override public boolean isCurrent() { return true; }
        @Override public boolean canInteractThisTick() { return false; }
        @Override public boolean tryClaimInteraction() { return false; }
    }

    /** 背包替身：手工登记格子里的物品堆。 */
    static final class FakeBag implements BackpackView {
        final List<BackpackStack> stacks = new ArrayList<>();

        void put(String itemId, int count) {
            stacks.add(new BackpackStack(itemId, count, 64, false, false, false, true));
        }

        @Override public List<BackpackStack> stacks() {
            return List.copyOf(stacks);
        }

        @Override public int usedSlots() {
            return stacks.size();
        }

        @Override public int totalSlots() {
            return 36;
        }
    }

    /** 现场替身：角色站哪、朝哪。 */
    static final class FakeWorld implements TravelWorldView {
        WorldPosition spot = WorldPosition.here(0, 64, 0);
        Target.Toward facing = Target.Toward.NORTH;

        @Override public WorldPosition currentSpot() {
            return spot;
        }

        @Override public Target.Toward currentFacing() {
            return facing;
        }

        /** 地表脚的高度；没摆为读不到（那一片没加载）。 */
        Integer surface;

        @Override public OptionalInt surfaceFeet(int x, int z) {
            return surface == null ? OptionalInt.empty() : OptionalInt.of(surface);
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

    /** 观察编号替身：手工登记编号与看得见的告示牌。 */
    static final class FakeSeen implements ReadsSeenTargets {
        private final Map<String, WorldPosition> seen = new HashMap<>();
        private final List<SceneSign> signs = new ArrayList<>();

        void put(String id, WorldPosition position) {
            seen.put(id, position);
        }

        /** 摆一块看得见的告示牌：正面几行字，方位东、距离按给的算。 */
        void sign(String id, WorldPosition position, int distance, String... frontLines) {
            signs.add(new SceneSign(id, "minecraft:oak_sign", position, "前方", "东", distance,
                    new SignLines(List.of(frontLines), List.of())));
        }

        @Override public Optional<WorldPosition> positionOf(String observationId) {
            return Optional.ofNullable(seen.get(observationId));
        }

        @Override public List<SceneSign> signs() {
            return List.copyOf(signs);
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
