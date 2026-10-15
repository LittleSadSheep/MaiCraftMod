// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.behavior.inventory.PicksUpDrops;
import org.maiwithu.maicraft.behavior.combat.DragonGeometry;
import org.maiwithu.maicraft.behavior.combat.Footwork;
import org.maiwithu.maicraft.behavior.combat.LiveBattleMoves;
import org.maiwithu.maicraft.behavior.combat.ModRangedWeapons;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.game.world.DropConfirmations;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 战斗动手的生产实现：打的那部分交给战斗引擎的生产动手（和自卫同一份），拾荒交给共用的捡掉落物，
 * 收没收进包按服务器的拾取包认。
 */
public final class LiveFightMoves implements FightMoves {

    /** 出手、射箭、举盾、迈步、寻路：和自卫共用一份实现。 */
    private final LiveBattleMoves battle;

    /** 掉落物的事实确认：拾荒按服务器的拾取包认东西进没进包。 */
    private final DropConfirmations confirmations;
    /** 捡掉落物：和采集、打猎共用的那一套。 */
    private final PicksUpDrops drops;

    /** @param weapons 联动的远程武器（土豆加农炮）：点名战斗时也照样用 */
    public LiveFightMoves(WalkTo walks, DropConfirmations confirmations, UseKeyProjection projection,
            ModRangedWeapons weapons, PicksUpDrops drops) {
        this.battle = new LiveBattleMoves(walks, projection, weapons);
        this.confirmations = confirmations;
        this.drops = drops;
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

    // 下面这些引擎后来才加的本事也照原样交给战斗引擎的生产动手：出手时走位不动镜头、走过去先把剑斧拿到手上、
    // 跳劈、撤不掉时垫高或天黑挖坑、把飞来的火球打回去。原先点名战斗没转过去，这几样只在自卫里有。
    @Override
    public void stepKeepingAim(TickContext context, Footwork.Step step) {
        battle.stepKeepingAim(context, step);
    }

    @Override public void arm(TickContext context, double distance) { battle.arm(context, distance); }
    @Override public Posture posture(TickContext context) { return battle.posture(context); }
    @Override public boolean canPillar(TickContext context) { return battle.canPillar(context); }
    @Override public boolean night(TickContext context) { return battle.night(context); }
    @Override public Action returnProjectile(int projectileId) { return battle.returnProjectile(projectileId); }
    @Override public Action pillarUp(TickContext context) { return battle.pillarUp(context); }

    // 打龙要的本事：砍龙头、远射水晶、算弹道、迈步不对视末影人、地表与区块、副手的盾。
    @Override public Action strikeHead(TickContext context, int dragonId) { return battle.strikeHead(context, dragonId); }

    @Override
    public Action shoot(TickContext context, int entityId, double maxRange) {
        return battle.shoot(context, entityId, maxRange);
    }

    @Override
    public boolean arcTo(TickContext context, int entityId, double maxRange) {
        return battle.arcTo(context, entityId, maxRange);
    }

    @Override
    public void stepAvertingGaze(TickContext context, int faceEntityId, Footwork.Step step) {
        battle.stepAvertingGaze(context, faceEntityId, step);
    }

    @Override public DragonGeometry.Surface surface(TickContext context) { return battle.surface(context); }
    @Override public boolean loaded(TickContext context, double x, double z) { return battle.loaded(context, x, z); }
    @Override public boolean hasShield(TickContext context) { return battle.hasShield(context); }

    // 经验：客户端收到的等级与总点数（服务器同步过来的）。
    @Override
    public Experience experience(TickContext context) {
        var self = context.player() == null ? null : context.player().localPlayer();
        return self == null ? null : new Experience(self.experienceLevel, self.totalExperience);
    }

    @Override public double[] selfPosition(TickContext context) { return battle.selfPosition(context); }

    @Override
    public long dropCursor(TickContext context) {
        var self = context.player() == null ? null : context.player().localPlayer();
        return self == null || confirmations == null ? 0 : confirmations.cursor(self);
    }

    @Override
    public Set<Integer> dropsLyingBefore(TickContext context, long cursor) {
        // 身边的掉落物里，开打前就躺着的：按服务器新增掉落的序号认，比开打早的不是这一仗带出来的。
        ClientLevel level = level(context);
        var self = context.player() == null ? null : context.player().localPlayer();
        Set<Integer> before = new HashSet<>();
        if (level == null || self == null) return before;
        for (int id : drops.nearby()) {
            Entity item = level.getEntity(id);
            if (item != null && (confirmations == null || !confirmations.spawnedAfter(self, item.getUUID(), cursor))) {
                before.add(id);
            }
        }
        return before;
    }

    @Override
    public Action pickUpNewSince(Set<Integer> before) {
        return drops.pickUpNewSince(before);
    }

    @Override
    public List<Gained> pickedUpSince(TickContext context, long cursor) {
        // 服务器说本玩家收了、而且是开打之后才冒出来的才算这一仗的战利品：路过顺手捡到的旧东西不算。
        var self = context.player() == null ? null : context.player().localPlayer();
        List<Gained> gained = new ArrayList<>();
        if (self == null || confirmations == null) return gained;
        for (DropConfirmations.Pickup pickup : confirmations.pickups(self, cursor)) {
            if (confirmations.spawnedAfter(self, pickup.uuid(), cursor)) {
                gained.add(new Gained(pickup.itemId(), pickup.amount()));
            }
        }
        return gained;
    }

    private static ClientLevel level(TickContext context) {
        var player = context.player();
        return player == null ? null : player.level();
    }
}
