// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 打架时手上那些事的生产实现，自卫和点名战斗共用一份：射一箭、举盾放盾、看身上有没有能射的和剑斧、
 * 打玩家时按出刀节拍挑走位环。
 */
public final class LiveCombatHands {

    /** 攻击充能到这么多就算好了：和原生攻击出手的门槛一样。 */
    private static final float ATTACK_READY = 0.95f;

    private final UseKeyProjection projection;
    private final ShieldGuard shield;
    private final ModRangedWeapons weapons;

    public LiveCombatHands(UseKeyProjection projection) {
        this(projection, ModRangedWeapons.none());
    }

    /** @param weapons 联动的远程武器（土豆加农炮）：上了弦的弩之后、弓之前用 */
    public LiveCombatHands(UseKeyProjection projection, ModRangedWeapons weapons) {
        this.projection = projection;
        this.shield = new ShieldGuard(projection);
        this.weapons = weapons;
    }

    /** 冲这一只射一箭（或用联动的远程武器打一发）。 */
    public Action shoot(int entityId) {
        return new RangedShot(entityId, projection, weapons);
    }

    public boolean canShoot(TickContext context) {
        LocalPlayer self = self(context);
        return self != null && RangedShot.canShoot(self, weapons);
    }

    public boolean hasMeleeWeapon(TickContext context) {
        LocalPlayer self = self(context);
        return self != null && WeaponChoice.pickMelee(WeaponCarriedReader.read(self), false).weapon()
                != WeaponChoice.Weapon.BARE_HANDS;
    }

    public boolean attackReady(TickContext context) {
        LocalPlayer self = self(context);
        return self == null || self.getAttackStrengthScale(0.0F) >= ATTACK_READY;
    }

    /** 打玩家按出刀节拍走位，打怪按怪的环。 */
    public CloseCombat.Ring ring(TickContext context, int entityId) {
        LocalPlayer self = self(context);
        Entity target = self == null ? null : self.level().getEntity(entityId);
        return target instanceof Player ? CloseCombat.Ring.player(attackReady(context)) : CloseCombat.Ring.MOB;
    }

    public void guard(TickContext context, double distance, boolean aboutToStrike) {
        shield.tick(context, distance, aboutToStrike);
    }

    /** 不打了：盾放下。 */
    public void stand() {
        shield.close();
    }

    private static LocalPlayer self(TickContext context) {
        return context.player() == null ? null : context.player().localPlayer();
    }
}
