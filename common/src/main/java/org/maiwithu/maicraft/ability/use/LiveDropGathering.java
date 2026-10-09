// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 顺手捡起的生产实现：出手后脚边新冒出来的掉落物（剪下来的羊毛），一件件走过去让原版拾取吸进包。
 *
 * <p>只捡这一下带出来的：出手前就躺在地上的东西不是这次的，不去碰。走路交给走到（不挖不垫，
 * 不会为捡一团羊毛走下悬崖）；东西进没进包以地上那件消失为准，限时没捡到如实失败，
 * 不把"走过去了"当成"捡到了"。
 */
final class LiveDropGathering implements UseSeams.GathersDrops {

    /** 只认近处的：交互掉出来的东西就在脚边，走远了的不算"顺手"。 */
    private static final double NEARBY_BLOCKS = 6.0;
    /** 每件东西的耐心；到点没捡到就放弃这一件，不在一件东西上耗着。 */
    private static final int GIVE_UP_TICKS = 100;

    private final Supplier<PlayerContext> context;
    private final WalkTo walks;

    LiveDropGathering(Supplier<PlayerContext> context, WalkTo walks) {
        this.context = Objects.requireNonNull(context, "context");
        this.walks = Objects.requireNonNull(walks, "walks");
    }

    @Override
    public Set<Integer> nearby() {
        LocalPlayer player = player();
        Set<Integer> ids = new HashSet<>();
        if (player == null) return ids;
        for (Entity entity : player.clientLevel.entitiesForRendering()) {
            if (entity instanceof ItemEntity && entity.distanceToSqr(player) <= NEARBY_BLOCKS * NEARBY_BLOCKS) {
                ids.add(entity.getId());
            }
        }
        return ids;
    }

    @Override
    public Optional<Action> collectNewSince(Set<Integer> before) {
        Set<Integer> fresh = new HashSet<>(nearby());
        fresh.removeAll(before);
        return fresh.isEmpty() ? Optional.empty() : Optional.of(new PickUp(before));
    }

    private LocalPlayer player() {
        PlayerContext current = context.get();
        return current == null ? null : current.localPlayer();
    }

    /** 一件件捡起新掉出来的东西：走到它脚下那一格，它从地上消失就是捡到了，下一件接着来。 */
    private final class PickUp implements Action {

        /** 出手前就在地上的，以及捡不到放弃了的：都不再去碰。 */
        private final Set<Integer> skip;
        private Integer current;
        private BlockPos walkingTo;
        private WalkRun walk;
        private int waited;
        private int missed;

        PickUp(Set<Integer> before) {
            this.skip = new HashSet<>(before);
        }

        @Override
        public ActionStatus tick(TickContext tick) {
            LocalPlayer player = player();
            if (player == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME, "这一刻掌握不到角色，捡不了", null));
            }
            Entity drop = current == null ? null : player.clientLevel.getEntity(current);
            if (current != null && (drop == null || !drop.isAlive())) {
                // 地上那件不在了：被原版吸进了包（也可能被别人拿走），接着捡下一件。
                current = null;
            } else if (current != null && ++waited > GIVE_UP_TICKS) {
                skip.add(current);
                missed++;
                current = null;
            }
            if (current == null && !pickNext(player)) {
                stopWalking();
                return missed == 0 ? ActionStatus.done() : ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                        "有 " + missed + " 件掉出的东西走过去了还在地上，先不捡了", null));
            }
            return walkToward(player.clientLevel.getEntity(current), tick);
        }

        // 挑下一件这次新掉出来的、离得最近的东西；没有了返回假。
        private boolean pickNext(LocalPlayer player) {
            Set<Integer> fresh = nearby();
            fresh.removeAll(skip);
            Entity best = null;
            for (int id : fresh) {
                Entity entity = player.clientLevel.getEntity(id);
                if (entity != null && (best == null || entity.distanceToSqr(player) < best.distanceToSqr(player))) {
                    best = entity;
                }
            }
            if (best == null) return false;
            current = best.getId();
            waited = 0;
            return true;
        }

        // 东西还在滚动：它换了格子就重新起步走向新的那一格。
        private ActionStatus walkToward(Entity drop, TickContext tick) {
            BlockPos cell = drop.blockPosition();
            if (walk == null || !cell.equals(walkingTo)) {
                stopWalking();
                walkingTo = cell;
                walk = walks.start(GoalCompiler.standOn(cell), TerrainPermit.WALK_ONLY);
            }
            if (walk.tick(tick) instanceof ActionStatus.Failed) {
                // 走不过去就别指望它自己吸过来了：放弃这一件，挑下一件。
                skip.add(current);
                missed++;
                current = null;
                stopWalking();
            }
            return ActionStatus.progressed();
        }

        private void stopWalking() {
            if (walk != null) {
                walk.close();
                walk = null;
                walkingTo = null;
            }
        }

        @Override public void pause() {
            if (walk != null) walk.pause();
        }

        @Override public void close() {
            stopWalking();
        }

        @Override
        public String describe() {
            return "捡起脚边新掉出的东西";
        }
    }
}
