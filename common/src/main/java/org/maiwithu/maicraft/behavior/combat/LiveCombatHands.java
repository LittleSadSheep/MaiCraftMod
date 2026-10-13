// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 打架时手上那些事的生产实现，自卫和点名战斗共用一份：射一箭、举盾放盾、看身上有没有能射的和剑斧、
 * 攻击充能好了没。
 */
public final class LiveCombatHands {

    /** 攻击充能到这么多就算好了：和原生攻击出手的门槛一样。 */
    private static final float ATTACK_READY = 0.95f;

    private final UseKeyProjection projection;
    private final ShieldGuard shield;
    private final ModRangedWeapons weapons;
    /** 对手离这么远以上，才为换武器打开背包。 */
    private static final double ARM_DISTANCE = 4.0;
    private PlayerContext current;
    private final ClientMovesToMainhand hands = new ClientMovesToMainhand(() -> this.current);
    /** 正在把剑斧拿到手上；没在换为 null。 */
    private Action arming;

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

    public void guard(TickContext context, double distance, boolean aboutToStrike) {
        shield.tick(context, distance, aboutToStrike);
    }

    /**
     * 走过去的路上把最好的剑斧拿到手上：在主背包就开背包换，换的那几刻不挥。只在对手离得够远时开始，
     * 贴脸了不为换武器开背包（照旧拿快捷栏里最好的打）。实机 10-13 铁剑在主背包，出手的那一刻离僵尸不到四格，
     * 一路拿着火把打，半分钟没打死，被尸壳打死。
     */
    public void arm(TickContext context, double distance) {
        LocalPlayer self = self(context);
        if (self == null) return;
        if (arming != null) {
            if (arming.tick(context) instanceof ActionStatus.Running) return;
            arming.close();
            arming = null;
            return;
        }
        if (distance <= ARM_DISTANCE) return;
        WeaponChoice.Picked best = WeaponChoice.pickMelee(WeaponCarriedReader.read(self), false);
        if (best.weapon() == WeaponChoice.Weapon.BARE_HANDS || best.slot() == self.getInventory().selected) return;
        arming = hands.actionToMainhand(best.itemId()).orElse(null);
    }

    /** 不打了：盾放下，换到一半的武器也收尾。 */
    public void stand() {
        shield.close();
        if (arming != null) arming.close();
        arming = null;
    }

    private LocalPlayer self(TickContext context) {
        current = context.player();
        return context.player() == null ? null : context.player().localPlayer();
    }
}
