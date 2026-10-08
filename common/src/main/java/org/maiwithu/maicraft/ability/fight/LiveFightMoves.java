// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.interaction.InteractionAction;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.interaction.Interaction;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 战斗动手的生产实现：原生攻击、走到、掉落物扫描与背包计数。
 *
 * <p>出手按游戏接口层的攻击交互走：充能够格才挥、换手当刻与目标无敌帧不挥。
 * 有主生物（有名字、被驯服、拴绳、围栏）的保护判断等处境视图接上后在许可检查点统一生效。
 */
final class LiveFightMoves implements FightMoves {

    private final WalkTo walks;

    LiveFightMoves(WalkTo walks) {
        this.walks = walks;
    }

    @Override
    public Action strike(TickContext context, int entityId) {
        Entity target = entity(context, entityId);
        var player = context.player();
        if (target == null || player == null || player.interactionSender() == null) {
            return TargetGone.INSTANCE;
        }
        Interaction attack = Interaction.attackEntity(player, player.interactionSender(),
                player.menuActions(), player.input(), target);
        return new InteractionAction(attack, "攻击 " + typeId(target));
    }

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

    @Override
    public List<Drop> dropsNear(TickContext context, double x, double y, double z, double radius) {
        ClientLevel level = level(context);
        if (level == null) {
            return List.of();
        }
        List<Drop> drops = new ArrayList<>();
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                new AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius))) {
            String id = BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString();
            drops.add(new Drop(item.getId(), id, item.getX(), item.getY(), item.getZ()));
        }
        return drops;
    }

    @Override
    public int carriedItemCount(TickContext context) {
        var self = context.player() == null ? null : context.player().localPlayer();
        if (self == null) {
            return 0;
        }
        int total = 0;
        for (var stack : self.getInventory().items) {
            total += stack.getCount();
        }
        return total + self.getOffhandItem().getCount();
    }

    private Entity entity(TickContext context, int entityId) {
        ClientLevel level = level(context);
        return level == null ? null : level.getEntity(entityId);
    }

    private static ClientLevel level(TickContext context) {
        var player = context.player();
        return player == null ? null : player.level();
    }

    private static String typeId(Entity entity) {
        return EntityType.getKey(entity.getType()).toString();
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
                            "走到了还没接上，逼近不了目标"));
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
            return walk == null ? "走向目标" : walk.describe();
        }
    }

    /** 目标已不在：本动作立刻完成，不打空拳。 */
    enum TargetGone implements Action {
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
