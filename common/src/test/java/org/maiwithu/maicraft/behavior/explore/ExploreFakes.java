// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.game.player.PlayerContext;

/** 探索的离线测试替身：摆一片假世界、演走到、收事件，不碰游戏对象。 */
final class ExploreFakes {

    private ExploreFakes() {}

    /** 本刻上下文替身：只有刻号。 */
    static final class TestTick implements TickContext {
        private long gameTick = 0;

        @Override public long gameTick() {
            return gameTick;
        }

        @Override public PlayerContext player() {
            throw new IllegalStateException("探索测试不应该碰到角色对象");
        }

        void nextTick() {
            gameTick++;
        }
    }

    /**
     * 假世界：列样本按坐标登记，没登记的列默认是已加载的平原；身体位置跟走到情况的脚走
     * （在走的时候），落脚点没动过时用登记的 spot。看得见与支撑按集合登记。
     */
    static final class FakeWorld implements ExplorationWorld {
        WorldPosition spot = new WorldPosition(0, 64, 0, "minecraft:overworld");
        Float yaw = 180f;
        String dimension = "minecraft:overworld";
        String defaultBiome = "minecraft:plains";
        /** 身体所在格的实测群系：默认不设，按身体所在列的采样给（真实世界两条路读数一致）；
         *  起点群系错记的复现把它摆成与采样不同。 */
        String bodyBiome;
        /** 只认登记过的列：其余一概未加载（摆稀疏世界用）。 */
        boolean onlyRegisteredColumns = false;
        FakeWalks walks;
        final Map<String, ColumnSample> columns = new HashMap<>();
        final Set<String> blocked = new HashSet<>();
        final Set<String> supported = new HashSet<>();
        List<Spotted> entitySightings = List.of();
        List<Spotted> structureClues = List.of();

        void putColumn(int x, int z, ColumnSample sample) {
            columns.put(x + "," + z, sample);
        }

        void putBiome(int x, int z, String biome) {
            putColumn(x, z, new ColumnSample(true, biome, 63, false, true, "minecraft:grass_block"));
        }

        void support(int x, int footY, int z) {
            supported.add(x + "," + footY + "," + z);
        }

        @Override public WorldPosition currentSpot() {
            BlockPos walking = walks == null ? null : walks.currentFeet();
            if (walking != null) {
                return new WorldPosition(walking.getX(), walking.getY(), walking.getZ(), dimension);
            }
            return new WorldPosition(spot.x(), spot.y(), spot.z(), dimension);
        }

        @Override public String currentBiome() {
            if (bodyBiome != null) {
                return bodyBiome;
            }
            WorldPosition here = currentSpot();
            return sampleColumn(here.x(), here.z()).biome();
        }

        @Override public Float facingYaw() {
            return yaw;
        }

        @Override public ColumnSample sampleColumn(int x, int z) {
            ColumnSample sample = columns.get(x + "," + z);
            if (sample != null) {
                return sample;
            }
            if (onlyRegisteredColumns) {
                return ColumnSample.unloaded();
            }
            return new ColumnSample(true, defaultBiome, 63, false, true, "minecraft:grass_block");
        }

        @Override public boolean sightClear(int x, int y, int z) {
            // 默认四下都看得见；登记进 blocked 的列被挡住，过不了视线闸。
            return !blocked.contains(x + "," + z);
        }

        @Override public boolean matches(String registeredId, List<String> selectors) {
            return registeredId != null && selectors.contains(registeredId.toLowerCase(Locale.ROOT));
        }

        @Override public boolean supportedAt(int x, int footY, int z) {
            return supported.contains(x + "," + footY + "," + z);
        }

        @Override public List<Spotted> entitySightings(List<String> entitySelectors) {
            return entitySightings;
        }

        @Override public List<Spotted> structureClues(List<String> structureSelectors) {
            return structureClues;
        }
    }

    /** 一段演出来的走到运行：按脚本逐刻交出走到情况。 */
    static final class ScriptedWalk implements WalkRun {
        private final Deque<WalkReport> reports = new ArrayDeque<>();
        private final Deque<Boolean> stopAnswers = new ArrayDeque<>();
        private WalkReport last = WalkReport.planning();
        /** 在路上但每刻都没有真实进展：寻路对着走不到的落点空转的演法。 */
        private boolean idle;
        int stopRequests;

        ScriptedWalk(WalkReport... reports) {
            for (WalkReport report : reports) {
                this.reports.add(report);
            }
        }

        /** 脚本演完后一直在路上空转，不报真实进展。 */
        ScriptedWalk idleWithoutProgress() {
            this.idle = true;
            return this;
        }

        ScriptedWalk stopAnswers(Boolean... answers) {
            for (Boolean answer : answers) {
                stopAnswers.add(answer);
            }
            return this;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (reports.isEmpty()) {
                return idle ? ActionStatus.running() : ActionStatus.progressed();
            }
            WalkReport next = reports.remove();
            last = next;
            return switch (next.state()) {
                case ARRIVED, STOPPED -> ActionStatus.done();
                case FAILED -> ActionStatus.failed(next.problem());
                default -> idle ? ActionStatus.running() : ActionStatus.progressed();
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

        @Override public void pause() { }

        @Override public void close() { }

        @Override public String describe() {
            return "演出来的走到";
        }
    }

    /** 走到替身：记下每次出发的目的柱与许可；最近一段走到情况的脚就是身体位置。 */
    static final class FakeWalks implements WalkTo {
        final List<BlockPos> started = new ArrayList<>();
        final List<TerrainPermit> permits = new ArrayList<>();
        private final Deque<ScriptedWalk> walks = new ArrayDeque<>();
        private ScriptedWalk current;

        void enqueue(ScriptedWalk walk) {
            walks.add(walk);
        }

        /** 最近一刻的脚下位置；还没出发（或只在算路）时给 null，身体用落脚点。 */
        BlockPos currentFeet() {
            if (current != null && current.report().feet() != null) {
                return current.report().feet();
            }
            return null;
        }

        /** 最近一段走到运行收段请求的次数（断言"取消在途段"用）。 */
        int currentStopRequests() {
            return current == null ? 0 : current.stopRequests;
        }

        @Override public WalkRun start(GoalCompiler.Compiled target, TerrainPermit permit) {
            permits.add(permit);
            BlockPos destination = target.goal().center();
            started.add(destination);
            if (!walks.isEmpty()) {
                current = walks.remove();
            } else {
                // 没登记脚本的段：按走不过去演，测试里不会走到这里还指望它动。
                current = new ScriptedWalk(WalkReport.planning(),
                        WalkReport.failed(Problem.of(Problem.Kind.UNREACHABLE, "没有脚本的段"),
                                destination, false));
            }
            return current;
        }
    }

    /** 事件替身：把发布的发现事件收下来。 */
    static final class FakeEvents implements TaskEventSink {
        final List<TaskEvent.Kind> kinds = new ArrayList<>();
        final List<String> messages = new ArrayList<>();

        @Override public void publish(TaskEvent.Kind kind, String message) {
            kinds.add(kind);
            messages.add(message);
        }
    }
}
