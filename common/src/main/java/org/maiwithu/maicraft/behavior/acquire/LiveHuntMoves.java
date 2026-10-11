// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.navigation.WalkTo;

import org.maiwithu.maicraft.behavior.combat.MeleeStrike;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.world.InteractionRange;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 狩猎出手的生产实现：原生攻击与走到。
 *
 * <p>出手和自卫、点名战斗是同一个近战出手：先把剑斧里最好的那把换到主手，再原生攻击——充能够格才挥、
 * 换手当刻与目标无敌帧不挥，这些逐刻确认都在交互里。够不够得着按角色的实体触及距离量，
 * 够不着由狩猎先走近；走位只走不改，追猎物不挖路不垫路。
 */
public final class LiveHuntMoves implements HuntMoves {

    /** 触及距离留的余量（格）：量到包围盒刚好在边上时准星常打不中，先多走半步。 */
    private static final double REACH_MARGIN = 0.4;

    private final WalkTo walks;

    public LiveHuntMoves(WalkTo walks) {
        this.walks = Objects.requireNonNull(walks, "walks");
    }

    @Override public Action strike(TickContext context, int entityId) {
        var player = context.player();
        var target = player == null || player.level() == null ? null : player.level().getEntity(entityId);
        if (target == null || player.interactionSender() == null) {
            // 目标已经不在或这一刻交不出交互：返回一个立刻结束的空动作，下一刻重新决定。
            return AlreadyOver.INSTANCE;
        }
        // 换武器 -> 出手：拿着镐打羊也能打，但有剑斧就先换上，和自卫、点名战斗同一套出手。
        return new MeleeStrike(entityId);
    }

    @Override public boolean inReach(TickContext context, int entityId) {
        var player = context.player();
        var self = player == null ? null : player.localPlayer();
        var target = self == null || player.level() == null ? null : player.level().getEntity(entityId);
        if (target == null) return false;
        // 眼睛到它包围盒最近一点的距离，留一点余量：贴着触及边缘时准星常常差一丝打不到。
        var eye = self.getEyePosition();
        double reach = InteractionRange.entityReach(self) - REACH_MARGIN;
        return target.getBoundingBox().distanceToSqr(eye) <= reach * reach;
    }

    @Override public Optional<double[]> positionOf(TickContext context, int entityId) {
        var player = context.player();
        var target = player == null || player.level() == null ? null : player.level().getEntity(entityId);
        if (target == null || target.isRemoved()) return Optional.empty();
        return Optional.of(new double[] {target.getX(), target.getY(), target.getZ()});
    }

    @Override public Action walkTo(double x, double y, double z) {
        return new WalkMove(walks, new double[] {x, y, z});
    }

    @Override public double[] selfPosition(TickContext context) {
        var self = context.player() == null ? null : context.player().localPlayer();
        return self == null ? new double[] {0, 0, 0}
                : new double[] {self.getX(), self.getY(), self.getZ()};
    }

    /** 第一刻才拿得到上下文的走到动作：把当刻上下文带进来再开走。 */
    private static final class WalkMove implements Action {
        private final WalkTo walks;
        private final double[] target;
        private Action walk;

        WalkMove(WalkTo walks, double[] target) {
            this.walks = walks;
            this.target = target;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (walk == null) {
                BlockPos cell = new BlockPos((int) Math.floor(target[0]), (int) Math.floor(target[1]),
                        (int) Math.floor(target[2]));
                walk = walks.start(GoalCompiler.near(cell, 1.0), TerrainPermit.WALK_ONLY);
            }
            return walk.tick(context);
        }

        @Override public void pause() {
            if (walk != null) walk.pause();
        }

        @Override public void close() {
            if (walk != null) walk.close();
        }

        @Override public String describe() {
            return walk == null ? "走向猎物" : walk.describe();
        }
    }

    /** 目标已经不在：本动作立刻完成，不打空拳。 */
    private enum AlreadyOver implements Action {
        INSTANCE;

        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.done();
        }

        @Override public String describe() {
            return "目标已不在";
        }
    }
}
