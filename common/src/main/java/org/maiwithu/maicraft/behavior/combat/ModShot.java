// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.inventory.ClientGearChanges;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.survival.spi.RangedWeapon;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 用联动的远程武器（土豆加农炮）打一发：武器拿到主手 → 原版会打出留着吃的口粮时，先把一件能打的非口粮弹药放到副手 →
 * 冷却没过就等 → 按枪口与方向修正算弹道、转过去对准 → 点一下发出去 → 弹药少了一件或武器进了冷却才算发出。
 * 一刻只推进一步；中没中不在这里认。
 */
final class ModShot implements Action {

    /** 等冷却、等弹道各最多这么多刻，过了这一发就不打了。 */
    private static final int MAX_WAIT_TICKS = 60;
    private static final int CONFIRM_TICKS = 20;
    private static final double MAX_RANGE = 32.0;

    private enum Stage { ARM, AMMO, AIM, FIRING, DONE }

    private final int targetId;
    private final ModRangedWeapons weapons;
    private final ModRangedWeapons.Ready ready;
    private PlayerContext current;
    private final ClientMovesToMainhand hands = new ClientMovesToMainhand(() -> this.current);
    private final ClientGearChanges gear = new ClientGearChanges(() -> this.current, hands);
    private Stage stage = Stage.ARM;
    private Action step;
    private PendingInteraction pending;
    private int waited;
    private boolean fired;
    private boolean aiming;
    private Problem failure;

    ModShot(int targetId, ModRangedWeapons weapons, ModRangedWeapons.Ready ready) {
        this.targetId = targetId;
        this.weapons = weapons;
        this.ready = ready;
    }

    boolean fired() {
        return fired;
    }

    boolean aiming() {
        return aiming;
    }

    @Override public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        current = player;
        if (failure != null) return ActionStatus.failed(failure);
        if (stage == Stage.DONE) return ActionStatus.done();
        if (player == null || player.localPlayer() == null || player.interactionSender() == null) return ActionStatus.running();
        Entity target = player.level() == null ? null : player.level().getEntity(targetId);
        if (target == null || !target.isAlive()) {
            stage = Stage.DONE;
            return ActionStatus.done();
        }
        if (pending != null && !pending.terminal()) {
            pending = player.interactionSender().poll(player, pending);
            if (!pending.terminal()) return ActionStatus.running();
        }
        return switch (stage) {
            case ARM -> runStep(context, () -> idOf(player.localPlayer().getMainHandItem()).equals(ready.weaponItem()) ? null
                    : hands.actionToMainhand(ready.weaponItem()).orElse(null), Stage.AMMO, "没能把" + ready.weaponItem() + "拿到手上");
            case AMMO -> runStep(context, () -> ready.offhandAmmo() == null
                    || idOf(player.localPlayer().getOffhandItem()).equals(ready.offhandAmmo()) ? null
                    : gear.wear(ready.offhandAmmo(), GearSlotName.OFFHAND).orElse(null), Stage.AIM,
                    "没能把" + ready.offhandAmmo() + "放到副手当弹药");
            case AIM -> aim(player, target);
            case FIRING -> settle();
            case DONE -> ActionStatus.done();
        };
    }

    // 换手、放副手这一步：要做就推进那个动作，不用做或做完进下一阶段。
    private ActionStatus runStep(TickContext context, Supplier<Action> make, Stage next, String failed) {
        if (step == null) {
            step = make.get();
            if (step == null) {
                stage = next;
                return ActionStatus.progressed();
            }
        }
        ActionStatus status = step.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        step.close();
        step = null;
        if (status instanceof ActionStatus.Failed problem) return fail(failed + "：" + problem.problem().message());
        stage = next;
        return ActionStatus.progressed();
    }

    // 对准：手上这件按一下会打出哪一发重新问一遍（换过副手）；口粮不打；冷却没过等；按枪口算弹道，对准了点一下。
    private ActionStatus aim(PlayerContext player, Entity target) {
        LocalPlayer self = player.localPlayer();
        ItemStack weapon = self.getMainHandItem();
        Optional<RangedWeapon.Shot> shot = ready.rule().nextShot(weapon, self);
        if (shot.isEmpty()) return fail("手上的" + ready.weaponItem() + "没有能打的弹药");
        if (weapons.ration(shot.get().ammoItem())) return fail("会打出留着吃的口粮 " + shot.get().ammoItem() + "，换不出别的弹药");
        if (self.getCooldowns().isOnCooldown(weapon.getItem())) return waitOr("冷却没过");
        RangedWeapon.Ballistics ballistics = shot.get().ballistics();
        Vec3 eye = self.getEyePosition();
        Vec3 muzzle = barrel(self, ballistics.muzzle());
        Optional<ArrowAim.Aim> aim = ArrowAim.find(player.level(), self, target, muzzle, ballistics.speed(),
                ballistics.gravity(), ballistics.drag(), ballistics.gravityBeforeDrag(), MAX_RANGE);
        if (aim.isEmpty()) return waitOr("没有弹道");
        // 发射方向 = 视线 + 修正再归一：倒过来，视线取发射方向减去修正（修正很小，一阶就够）。
        Vec3 correction = barrel(self, ballistics.aimCorrection()).subtract(eye);
        Vec3 look = aim.get().direction().subtract(correction).normalize();
        aiming = true;
        player.input().lookAt(self, eye.add(look.scale(64.0)));
        if (ArrowAim.angleDegrees(self.getViewVector(1.0f), look) > RangedShot.AIM_TOLERANCE_DEGREES
                || !player.canInteractThisTick()) {
            return ActionStatus.progressed();
        }
        String ammo = shot.get().ammoItem();
        int before = carried(self, ammo);
        pending = player.interactionSender().useItem(player, InteractionHand.MAIN_HAND,
                c -> carried(c.localPlayer(), ammo) < before || c.localPlayer().getCooldowns().isOnCooldown(weapon.getItem())
                        ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING, CONFIRM_TICKS);
        stage = Stage.FIRING;
        return ActionStatus.progressed();
    }

    private ActionStatus settle() {
        aiming = false;
        if (pending != null && pending.status() == PendingInteraction.Status.CONFIRMED_APPLIED) {
            fired = true;
            stage = Stage.DONE;
            return ActionStatus.done();
        }
        return fail("发射没得到游戏确认：" + (pending == null ? "" : pending.detail()));
    }

    // 等冷却、等弹道：等太久这一发就不打了（不算失败，交给战斗换个时机或换武器）。
    private ActionStatus waitOr(String why) {
        if (++waited < MAX_WAIT_TICKS) return ActionStatus.running();
        aiming = false;
        stage = Stage.DONE;
        return ActionStatus.done();
    }

    /**
     * 枪口这类"右手持枪、朝前"写的偏移落到世界里：和 Create 的 getGunBarrelVec 一样从眼睛出发，
     * 主手在右边时 x 取反，先绕俯仰再绕偏航转过去。
     */
    static Vec3 barrel(LocalPlayer self, Vec3 rightHandForward) {
        Vec3 eye = self.getEyePosition();
        if (rightHandForward.lengthSqr() < 1.0e-9) return eye;
        float yaw = (float) (self.getYRot() / -180.0 * Math.PI);
        float pitch = (float) (self.getXRot() / -180.0 * Math.PI);
        int flip = self.getMainArm() == HumanoidArm.RIGHT ? -1 : 1;
        Vec3 local = new Vec3(flip * rightHandForward.x, rightHandForward.y, rightHandForward.z);
        return eye.add(local.xRot(pitch).yRot(yaw));
    }

    private static int carried(LocalPlayer self, String itemId) {
        int total = 0;
        for (ItemStack stack : self.getInventory().items) if (idOf(stack).equals(itemId)) total += stack.getCount();
        if (idOf(self.getOffhandItem()).equals(itemId)) total += self.getOffhandItem().getCount();
        return total;
    }

    private static String idOf(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private ActionStatus fail(String reason) {
        aiming = false;
        failure = Problem.of(Problem.Kind.REFUSED_BY_GAME, reason);
        stage = Stage.DONE;
        return ActionStatus.failed(failure);
    }

    @Override public void pause() {
        if (step != null) step.pause();
    }

    @Override public void close() {
        if (step != null) step.close();
    }

    @Override public Interruptibility interruptibility() {
        return stage == Stage.FIRING ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    @Override public String describe() {
        return switch (stage) {
            case ARM -> "把" + ready.weaponItem() + "拿到手上";
            case AMMO -> "把" + ready.offhandAmmo() + "放到副手当弹药";
            case AIM -> "端着" + ready.weaponItem() + "瞄准";
            case FIRING -> "发射";
            case DONE -> fired ? "打出一发" : "这一发没打";
        };
    }
}
