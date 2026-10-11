// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 自卫的动手实现：原生攻击与走到。
 *
 * <p>出手按游戏接口层的攻击交互走——充能够格才挥、目标无敌帧不挥，这些逐刻确认都在交互里；
 * 走位只走不改，追击不挖路不垫路。
 */
public final class LiveCombatMoves implements SelfDefenseTask.CombatMoves {

    private final WalkTo walks;
    /** 射箭、举盾、看身上有没有弓和剑斧：和点名战斗共用一份。 */
    private final LiveCombatHands hands;

    public LiveCombatMoves(WalkTo walks, UseKeyProjection projection) {
        this.walks = walks;
        this.hands = new LiveCombatHands(projection);
    }

    @Override
    public Action strike(TickContext context, int entityId) {
        Entity target = sensesEntity(context, entityId);
        if (target == null || context.player() == null || context.player().interactionSender() == null) {
            // 目标已经不在或这一刻交不出交互：返回一个立刻结束的空动作，下一刻重新决定。
            return AlreadyOver.INSTANCE;
        }
        // 先把挑好的近战武器拿到主手再挥：和战斗共用一套出手。
        return new MeleeStrike(entityId);
    }

    @Override
    public void step(TickContext context, int entityId, float forward) {
        // 面朝它往前凑或往后退一刻；那一步脚下没路就站住。
        CombatStepping.step(context, entityId, forward);
    }

    @Override
    public Action shoot(TickContext context, int entityId) {
        return hands.shoot(entityId);
    }

    @Override
    public boolean canShoot(TickContext context) {
        return hands.canShoot(context);
    }

    @Override
    public boolean hasMeleeWeapon(TickContext context) {
        return hands.hasMeleeWeapon(context);
    }

    @Override
    public boolean attackReady(TickContext context) {
        return hands.attackReady(context);
    }

    @Override
    public CloseCombat.Ring ring(TickContext context, int entityId) {
        return hands.ring(context, entityId);
    }

    @Override
    public void guard(TickContext context, int entityId, double distance, boolean aboutToStrike) {
        // 对手近、自己还没到出手的时候举盾，要挥了先放下；副手空着、对手还远就先把盾装上。
        hands.guard(context, distance, aboutToStrike);
    }

    @Override
    public Action walkTo(double x, double y, double z) {
        // 生产实现需要当刻上下文才能开走；走到在动作的第一刻拿上下文，见 WalkMove。
        return new WalkMove(new double[] {x, y, z});
    }

    @Override
    public double[] selfPosition(TickContext context) {
        var self = context.player() == null ? null : context.player().localPlayer();
        return self == null ? new double[] {0, 0, 0}
                : new double[] {self.getX(), self.getY(), self.getZ()};
    }

    private Entity sensesEntity(TickContext context, int entityId) {
        return context.player() == null || context.player().level() == null
                ? null : context.player().level().getEntity(entityId);
    }


    /** 第一刻才拿得到上下文的走到动作：把当刻上下文带进来再开走。 */
    private final class WalkMove implements Action {
        private final double[] target;
        private Action walk;

        private WalkMove(double[] target) {
            this.target = target;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            if (walk == null) {
                if (walks == null) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED,
                            "走到了还没接上，追击走不了；守在原地迎击"));
                }
                BlockPos cell = new BlockPos((int) Math.floor(target[0]), (int) Math.floor(target[1]),
                        (int) Math.floor(target[2]));
                walk = walks.start(GoalCompiler.near(cell, 1.5), TerrainPermit.WALK_ONLY);
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
            return walk == null ? "走向追击点" : walk.describe();
        }
    }

    /** 目标已经不在：本动作立刻完成，不打空拳。 */
    private enum AlreadyOver implements Action {
        INSTANCE;

        @Override
        public ActionStatus tick(TickContext context) {
            return ActionStatus.done();
        }

        @Override
        public String describe() {
            return "目标已不在";
        }
    }
}
