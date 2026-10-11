// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.behavior.combat.Footwork;
import org.maiwithu.maicraft.behavior.combat.LiveBattleMoves;
import org.maiwithu.maicraft.behavior.combat.ModRangedWeapons;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.game.world.DropConfirmations;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 战斗动手的生产实现：打的那部分交给战斗引擎的生产动手（和自卫同一份），这里只加掉落物扫描与拾取确认。
 */
public final class LiveFightMoves implements FightMoves {

    /** 出手、射箭、举盾、迈步、寻路：和自卫共用一份实现。 */
    private final LiveBattleMoves battle;

    /** 掉落物的事实确认：拾荒按服务器的拾取包认东西进没进包。 */
    private final DropConfirmations confirmations;

    /** @param weapons 联动的远程武器（土豆加农炮）：点名战斗时也照样用 */
    public LiveFightMoves(WalkTo walks, DropConfirmations confirmations, UseKeyProjection projection,
            ModRangedWeapons weapons) {
        this.battle = new LiveBattleMoves(walks, projection, weapons);
        this.confirmations = confirmations;
    }

    @Override public Action strike(TickContext context, int entityId) { return battle.strike(context, entityId); }
    @Override public Action shoot(TickContext context, int entityId) { return battle.shoot(context, entityId); }
    @Override public boolean canShoot(TickContext context) { return battle.canShoot(context); }
    @Override public boolean hasMeleeWeapon(TickContext context) { return battle.hasMeleeWeapon(context); }
    @Override public boolean attackReady(TickContext context) { return battle.attackReady(context); }

    @Override
    public void guard(TickContext context, double distance, boolean aboutToStrike) {
        battle.guard(context, distance, aboutToStrike);
    }

    @Override public void lowerGuard() { battle.lowerGuard(); }
    @Override public Footwork.Ground ground(TickContext context) { return battle.ground(context); }

    @Override
    public void step(TickContext context, int faceEntityId, Footwork.Step step) {
        battle.step(context, faceEntityId, step);
    }

    @Override
    public boolean standable(TickContext context, int x, int y, int z) {
        return battle.standable(context, x, y, z);
    }

    @Override public String mainHand(TickContext context) { return battle.mainHand(context); }

    @Override public Action walk(GoalCompiler.Compiled goal) { return battle.walk(goal); }

    @Override public double[] selfPosition(TickContext context) { return battle.selfPosition(context); }

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
    public long dropCursor(TickContext context) {
        var self = context.player() == null ? null : context.player().localPlayer();
        return self == null || confirmations == null ? 0 : confirmations.cursor(self);
    }

    @Override
    public int pickedUp(TickContext context, int entityId, long sinceCursor) {
        var self = context.player() == null ? null : context.player().localPlayer();
        return self == null || confirmations == null ? 0 : confirmations.pickedUpEntity(self, entityId, sinceCursor);
    }

    private static ClientLevel level(TickContext context) {
        var player = context.player();
        return player == null ? null : player.level();
    }
}
