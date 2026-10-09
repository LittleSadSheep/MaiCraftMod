// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 夜晚自保与贴边退避的动手实现：挖三填一交给 {@link LiveBurrow}，退避用走到。
 */
public final class LiveNightAndEdgeMoves {

    private LiveNightAndEdgeMoves() {}

    /** 夜晚自保（挖三填一）的动手实现。 */
    public static NightfallNeed.BurrowMoves burrow(LiveBurrow moves, TaskEventSink events) {
        return () -> new BurrowInTask(moves, events);
    }

    /** 贴边退避的动手实现。 */
    public static EdgeProximityNeed.RetreatMoves retreat(WalkTo walks, TaskEventSink events) {
        return safeSpot -> new StepBackTask(safeSpot, new EdgeSteps(walks), events);
    }

    /** 贴边退避的走到。 */
    private record EdgeSteps(WalkTo walks) implements StepBackTask.Moves {

        @Override
        public Action walkTo(double x, double y, double z) {
            return new WalkMove(walks, new double[] {x, y, z});
        }

        @Override
        public double[] selfPosition(TickContext context) {
            var self = context.player() == null ? null : context.player().localPlayer();
            return self == null ? new double[] {0, 0, 0}
                    : new double[] {self.getX(), self.getY(), self.getZ()};
        }
    }

    /** 第一刻才拿得到上下文的走到动作。 */
    private static final class WalkMove implements Action {
        private final WalkTo walks;
        private final double[] target;
        private Action walk;

        private WalkMove(WalkTo walks, double[] target) {
            this.walks = walks;
            this.target = target;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            if (walk == null) {
                if (walks == null) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED,
                            "走到了还没接上，退不了；先站住不动"));
                }
                BlockPos cell = new BlockPos((int) Math.floor(target[0]), (int) Math.floor(target[1]),
                        (int) Math.floor(target[2]));
                walk = walks.start(GoalCompiler.near(cell, 1.0), TerrainPermit.WALK_ONLY);
            }
            return walk.tick(context);
        }

        @Override
        public void pause() {
            if (walk != null) walk.pause();
        }

        @Override
        public void close() {
            if (walk != null) walk.close();
        }

        @Override
        public String describe() {
            return walk == null ? "走向安全处" : walk.describe();
        }
    }
}
