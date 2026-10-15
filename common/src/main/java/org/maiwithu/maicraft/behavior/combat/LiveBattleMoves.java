// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.behavior.navigation.ScaffoldBlocks;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 战斗引擎动手的生产实现，点名战斗与自卫共用一份：出手按游戏接口层的原生攻击（先换上挑好的近战武器），
 * 射箭、举盾按 {@link LiveCombatHands}，迈步按 {@link CombatStepping}，寻路按走到（只走不改地形）。
 */
public final class LiveBattleMoves implements BattleMoves {

    private final WalkTo walks;
    private final LiveCombatHands hands;

    /** @param weapons 联动的远程武器（土豆加农炮）：上了弦的弩之后、弓之前用 */
    public LiveBattleMoves(WalkTo walks, UseKeyProjection projection, ModRangedWeapons weapons) {
        this.walks = walks;
        this.hands = new LiveCombatHands(projection, weapons);
    }

    @Override
    public Action strike(TickContext context, int entityId) {
        var player = context.player();
        Entity target = player == null || player.level() == null ? null : player.level().getEntity(entityId);
        if (target == null || player.interactionSender() == null) {
            // 目标已经不在或这一刻交不出交互：立刻收场，不打空拳，也不记成打中。
            return Gone.INSTANCE;
        }
        return new MeleeStrike(entityId);
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
    public void guard(TickContext context, double distance, boolean aboutToStrike) {
        hands.guard(context, distance, aboutToStrike);
    }

    @Override
    public void arm(TickContext context, double distance) {
        hands.arm(context, distance);
    }

    @Override
    public void lowerGuard() {
        hands.stand();
    }

    @Override
    public Footwork.Ground ground(TickContext context) {
        return CombatStepping.ground(context);
    }

    @Override
    public void step(TickContext context, int faceEntityId, Footwork.Step step) {
        CombatStepping.move(context, faceEntityId, step);
    }

    @Override
    public void stepKeepingAim(TickContext context, Footwork.Step step) {
        CombatStepping.move(context, CombatStepping.KEEP_AIM, step);
    }

    // 没有昼夜的维度（下界、末地）不算天黑：那里挖坑等不到天亮。实机 10-15 下界被恶魂打，
    // 自卫要"挖坑熬到天亮"，挖坑时走进火球点的火里烧死。
    @Override
    public boolean night(TickContext context) {
        var self = context.player() == null ? null : context.player().localPlayer();
        return self != null && !self.level().dimensionType().hasFixedTime() && !self.level().isDay();
    }

    @Override
    public Action returnProjectile(int projectileId) {
        return new ProjectileReturn(projectileId);
    }

    @Override
    public Action walk(GoalCompiler.Compiled goal) {
        return new Walk(goal);
    }

    @Override
    public double[] selfPosition(TickContext context) {
        var self = context.player() == null ? null : context.player().localPlayer();
        return self == null ? new double[] {0, 0, 0} : new double[] {self.getX(), self.getY(), self.getZ()};
    }

    @Override
    public boolean standable(TickContext context, int x, int y, int z) {
        var level = context.player() == null ? null : context.player().level();
        BlockPos feet = new BlockPos(x, y, z);
        return level != null && level.isLoaded(feet) && level.isLoaded(feet.below())
                && BlockPassability.isDryStandable(level, feet);
    }

    @Override
    public String mainHand(TickContext context) {
        var self = context.player() == null ? null : context.player().localPlayer();
        if (self == null || self.getMainHandItem().isEmpty()) return "空手";
        return BuiltInRegistries.ITEM.getKey(self.getMainHandItem().getItem()).toString();
    }

    @Override
    public boolean canPillar(TickContext context) {
        var self = context.player() == null ? null : context.player().localPlayer();
        if (self == null || !self.level().isDay()) return false;
        // 身上能拿去垫的废料够三块，头顶三格空着（垫上去不撞头）。
        Map<String, Integer> carried = new HashMap<>();
        for (var stack : self.getInventory().items) {
            if (!stack.isEmpty()) carried.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        int blocks = 0;
        for (String id : ScaffoldBlocks.usable(id -> carried.getOrDefault(id, 0), ItemsToKeep.NOTHING)) blocks += carried.get(id);
        BlockPos feet = self.blockPosition();
        for (int up = 2; up <= 4; up++) {
            BlockPos cell = feet.above(up);
            if (!self.level().getBlockState(cell).getCollisionShape(self.level(), cell).isEmpty()) return false;
        }
        return blocks >= 3;
    }

    @Override
    public Action pillarUp(TickContext context) {
        var self = context.player() == null ? null : context.player().localPlayer();
        if (self == null || walks == null) return null;
        // 交给走到：目标是脚下这一列往上三格，只垫不挖，寻路会原地起跳往脚下垫方块。
        BlockPos top = self.blockPosition().above(3);
        return new Action() {
            private Action run;

            @Override public ActionStatus tick(TickContext tick) {
                if (run == null) run = walks.start(GoalCompiler.standOn(top), TerrainPermit.TEMPORARY);
                return run.tick(tick);
            }

            @Override public void pause() {
                if (run != null) run.pause();
            }

            @Override public void close() {
                if (run != null) run.close();
            }

            @Override public String describe() {
                return "就地往上垫三格";
            }
        };
    }

    @Override
    public Posture posture(TickContext context) {
        var self = context.player() == null ? null : context.player().localPlayer();
        if (self == null) return Posture.GROUNDED;
        boolean onGround = self.onGround();
        boolean falling = !onGround && self.getDeltaMovement().y < 0 && self.fallDistance > 0;
        // 能起跳：站在地上，不在水里岩浆里、没在爬梯子、没骑着东西，头顶那一格空着（跳起来不撞头）。
        BlockPos head = self.blockPosition().above(2);
        boolean headroom = self.level().getBlockState(head).getCollisionShape(self.level(), head).isEmpty();
        boolean canJump = onGround && headroom && !self.isInWater() && !self.isInLava() && !self.onClimbable()
                && !self.isPassenger();
        return new Posture(onGround, falling, canJump, self.getAttackStrengthScale(0.0F));
    }

    /** 第一刻才开走的寻路：走到没接上时如实失败。 */
    private final class Walk implements Action {
        private final GoalCompiler.Compiled goal;
        private Action run;

        private Walk(GoalCompiler.Compiled goal) {
            this.goal = goal;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            if (run == null) {
                if (walks == null) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED, "走到还没接上，战斗里走不了远路"));
                }
                run = walks.start(goal, TerrainPermit.WALK_ONLY);
            }
            return run.tick(context);
        }

        @Override
        public void pause() {
            if (run != null) run.pause();
        }

        @Override
        public void close() {
            if (run != null) run.close();
        }

        @Override
        public String describe() {
            return run == null ? "准备寻路" : run.describe();
        }
    }

    /** 目标已不在：本动作立刻失败，不打空拳。 */
    private enum Gone implements Action {
        INSTANCE;

        @Override
        public ActionStatus tick(TickContext context) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "目标已不在"));
        }

        @Override
        public String describe() {
            return "目标已不在";
        }
    }
}
