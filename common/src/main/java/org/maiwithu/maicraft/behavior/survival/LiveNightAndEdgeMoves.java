// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.interaction.InteractionAction;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.interaction.Interaction;
import org.maiwithu.maicraft.game.world.WorldTime;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 夜晚自保与贴边退避的动手实现：封顶用原生放方块，退避用走到。
 *
 * <p>封顶只对手里已有的方块动手：不为一块方块摸黑找材料。夜里是否还在熬按可睡时段判断，
 * 威胁散没散交给威胁评估的结论——这里不重复判断。
 */
public final class LiveNightAndEdgeMoves {

    private LiveNightAndEdgeMoves() {}

    /** 夜晚自保的动手实现。 */
    public static NightfallNeed.BurrowMoves burrow(TaskEventSink events) {
        return () -> new BurrowInTask(new BurrowStatus(), events);
    }

    /** 贴边退避的动手实现。 */
    public static EdgeProximityNeed.RetreatMoves retreat(WalkTo walks, TaskEventSink events) {
        return safeSpot -> new StepBackTask(safeSpot, new EdgeSteps(walks), events);
    }

    /** 自保任务的现场：还在熬的夜里吗、手上有方块吗、往头顶放一块。 */
    private static final class BurrowStatus implements BurrowInTask.NightStatus {

        @Override
        public boolean stillNight(TickContext context) {
            var level = context.player() == null ? null : context.player().level();
            // 威胁散没散由威胁评估在需求一侧判断；这里只看时间还在不在可睡时段。
            return level != null && WorldTime.canAttemptSleep(level);
        }

        @Override
        public boolean holdingBlock(TickContext context) {
            var self = context.player() == null ? null : context.player().localPlayer();
            return self != null && WeaponCarriedReader.isPlaceableBlock(self.getMainHandItem());
        }

        @Override
        public Action sealOverhead(TickContext context) {
            var player = context.player();
            var self = player.localPlayer();
            BlockPos above = self.blockPosition().above(2);
            if (!level(context).getBlockState(above).getCollisionShape(level(context), above).isEmpty()) {
                return DoneNothing.DONE_NOTHING;
            }
            // 点脚下两格下那格的顶面，方块就落进头顶那格；预置命中点跳过重新瞄准。
            BlockPos below = above.below();
            BlockHitResult hit = new BlockHitResult(
                    Vec3.atCenterOf(below).add(0.0, 0.5, 0.0), Direction.UP, below, false);
            Interaction place = Interaction.useBlock(player, player.interactionSender(),
                    player.menuActions(), player.input(), hit, InteractionHand.MAIN_HAND);
            return new InteractionAction(place, "往头顶放方块封顶");
        }

        private static ClientLevel level(TickContext context) {
            return context.player().level();
        }
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

    /** 立刻完成的空动作。 */
    enum DoneNothing implements Action {
        DONE_NOTHING;

        @Override
        public ActionStatus tick(TickContext context) {
            return ActionStatus.done();
        }

        @Override
        public String describe() {
            return "头顶已封";
        }
    }

}
