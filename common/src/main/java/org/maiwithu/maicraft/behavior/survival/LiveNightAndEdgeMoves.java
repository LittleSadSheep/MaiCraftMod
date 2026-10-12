// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;

import org.maiwithu.maicraft.behavior.inventory.PicksUpDrops;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 夜晚自保、贴边退避与回去捡东西的动手实现：挖三填一交给 {@link LiveBurrow}，退避与跑回去用走到。
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

    /**
     * 回死的地方捡东西的动手实现：跑回去用走到（按默认许可能挖能垫，路上被挡就开路），
     * 捡用共用的捡掉落物。
     */
    public static LostItemsNeed.RecoversDrops lostItems(WalkTo walks, PicksUpDrops drops, TaskEventSink events) {
        RecoverDropsTask.Moves moves = new RecoverDropsTask.Moves() {
            @Override public Action runTo(double x, double y, double z) {
                return new WalkMove(walks, new double[] {x, y, z}, TerrainPermit.NATURAL, 2.0, "跑回死的地方");
            }

            @Override public PicksUpDrops drops() {
                return drops;
            }

            @Override public double[] selfPosition(TickContext context) {
                LocalPlayer self = context.player() == null ? null : context.player().localPlayer();
                return self == null ? null : new double[] {self.getX(), self.getY(), self.getZ()};
            }

            @Override public int carriedStacks(TickContext context) {
                LocalPlayer self = context.player() == null ? null : context.player().localPlayer();
                if (self == null) return 0;
                int stacks = 0;
                Inventory inventory = self.getInventory();
                for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                    if (!inventory.getItem(slot).isEmpty()) stacks++;
                }
                return stacks;
            }
        };
        return (death, expiresAtTick) -> new RecoverDropsTask(death, expiresAtTick, moves, events);
    }

    /** 读身体：本地玩家此刻在哪、哪个维度、身上（主背包、副手、盔甲）有几格东西。 */
    public static LostItemsNeed.ReadsPlayer players() {
        return new LostItemsNeed.ReadsPlayer() {
            @Override public LostItemsNeed.PlayerNow read(TickContext context) {
                LocalPlayer self = context.player() == null ? null : context.player().localPlayer();
                if (self == null) return null;
                int stacks = 0;
                Inventory inventory = self.getInventory();
                for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                    if (!inventory.getItem(slot).isEmpty()) stacks++;
                }
                return new LostItemsNeed.PlayerNow(self, self.getX(), self.getY(), self.getZ(),
                        self.level().dimension().location().toString(), context.gameTick(), stacks);
            }

            @Override public boolean died(Object identity) {
                return identity instanceof LocalPlayer old && old.isDeadOrDying();
            }
        };
    }

    /** 贴边退避的走到。 */
    private record EdgeSteps(WalkTo walks) implements StepBackTask.Moves {

        @Override
        public Action walkTo(double x, double y, double z) {
            return new WalkMove(walks, new double[] {x, y, z}, TerrainPermit.WALK_ONLY, 1.0, "走向安全处");
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
        private final TerrainPermit permit;
        private final double within;
        private final String what;
        private Action walk;
        /** 被别的临时任务打断过：回来时这一段路作废，从站的地方重新算路。 */
        private boolean interrupted;

        private WalkMove(WalkTo walks, double[] target, TerrainPermit permit, double within, String what) {
            this.walks = walks;
            this.target = target;
            this.permit = permit;
            this.within = within;
            this.what = what;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            // 跑回去捡东西、躲夜路上被自卫打断：走到停稳后只会报"已停下"，原先这一下被当成"没走到"记一次失败，
            // 打断三回就放弃捡东西。回来先把停下的那一段收掉，从站的地方重新上路。
            if (interrupted) {
                interrupted = false;
                if (walk != null) walk.close();
                walk = null;
            }
            if (walk == null) {
                if (walks == null) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED,
                            "走到了还没接上，" + what + "走不了；先站住不动"));
                }
                BlockPos cell = new BlockPos((int) Math.floor(target[0]), (int) Math.floor(target[1]),
                        (int) Math.floor(target[2]));
                walk = walks.start(GoalCompiler.near(cell, within), permit);
            }
            return walk.tick(context);
        }

        @Override
        public void pause() {
            if (walk != null) walk.pause();
            interrupted = true;
        }

        @Override
        public void close() {
            if (walk != null) walk.close();
        }

        @Override
        public String describe() {
            return walk == null ? what : walk.describe();
        }
    }
}
