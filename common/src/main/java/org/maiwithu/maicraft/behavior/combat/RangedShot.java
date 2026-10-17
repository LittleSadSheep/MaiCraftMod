// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.List;
import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 射一箭：把弓或弩拿到主手 → 弓按住拉满、弩按住装填再松开 → 算弹道、转过去对准 → 弓松手放箭、弩再点一下射出。
 * 一刻只推进一步，每一下都等游戏确认。
 *
 * <p>弓拉满（20 刻）才放，放的那一刻视线要对准算出的弹道（差不到 1.5 度）；拉的时候只在快放手前几刻才转头，
 * 免得每刻转向把走位带偏。拉满了等弹道（对方躲到墙后、被别的生物挡着）就接着拉着等，拉了三秒还没有弹道就取消。
 * 取消用切换快捷栏（原版切槽停止使用、不放箭），不能用松手——松开弓就是放箭。
 * 射没射出去按游戏确认认（弓确认停止使用、弩确认手里的弩不再上弦），中没中不在这里认。
 */
public final class RangedShot implements Action, RangedCombat.Fires {

    /** 弓拉满的刻数：拉满才有满速和暴击。 */
    static final int FULL_DRAW_TICKS = 20;
    /** 快放手前几刻开始转头对准。 */
    private static final int AIM_LEAD_TICKS = 6;
    /** 拉满了等弹道最多等到这么多刻，再没有就取消。 */
    private static final int MAX_DRAW_TICKS = 60;
    /** 装弩最多等这么久：原版快速装填前是 25 刻。 */
    private static final int LOAD_TIMEOUT_TICKS = 80;
    /** 弩上弦后等弹道最多等这么久。 */
    private static final int MAX_AIM_TICKS = 60;
    /** 视线和弹道差不到这么多度才放。 */
    static final double AIM_TOLERANCE_DEGREES = 1.5;
    /** 平常比这远不算弹道。 */
    public static final double MAX_RANGE = 32.0;
    private static final int CONFIRM_TICKS = 20;

    private enum Stage { ARM, START, DRAW, LOAD, LOADING_RELEASE, AIM, FIRING, DONE }

    private final int targetId;
    private final UseKeyProjection projection;
    private final ModRangedWeapons weapons;
    /** 这一箭比这远不算弹道：平常 32 格，仰射柱顶的末影水晶放到 48 格。 */
    private final double maxRange;
    /** 挑中联动的远程武器时，这一发整个交给它。 */
    private ModShot modShot;
    private PlayerContext current;
    private final ClientMovesToMainhand hands = new ClientMovesToMainhand(() -> this.current);
    private Stage stage = Stage.ARM;
    private Action arming;
    private PendingInteraction pending;
    private ItemStack heldAtStart = ItemStack.EMPTY;
    private int held;
    private int aimWaited;
    private boolean fired;
    private boolean crossbow;
    private Problem failure;

    public RangedShot(int targetId, UseKeyProjection projection) {
        this(targetId, projection, ModRangedWeapons.none());
    }

    public RangedShot(int targetId, UseKeyProjection projection, ModRangedWeapons weapons) {
        this(targetId, projection, weapons, MAX_RANGE);
    }

    /** @param maxRange 比这远不算弹道（站在爆炸范围外仰射柱顶的末影水晶要比平常远） */
    public RangedShot(int targetId, UseKeyProjection projection, ModRangedWeapons weapons, double maxRange) {
        this.targetId = targetId;
        this.projection = projection;
        this.weapons = weapons;
        this.maxRange = maxRange;
    }

    /** 这一箭射出去了（游戏确认了放箭或弩发射）。 */
    @Override
    public boolean fired() {
        return modShot != null ? modShot.fired() : fired;
    }

    /** 正在转头对准、快要放了：走位这几刻站住，别把准星带偏。 */
    public boolean aiming() {
        if (modShot != null) return modShot.aiming();
        return stage == Stage.AIM || stage == Stage.DRAW && held >= FULL_DRAW_TICKS - AIM_LEAD_TICKS;
    }

    /** 身上有没有能射的：有箭的弓、上了弦或有箭可装的弩。 */
    public static boolean canShoot(LocalPlayer self) {
        return canShoot(self, ModRangedWeapons.none());
    }

    /** 同上，再算上联动的远程武器（有能打的非口粮弹药）。 */
    public static boolean canShoot(LocalPlayer self, ModRangedWeapons weapons) {
        return pick(self).isPresent() || weapons.ready(self, 0.0).isPresent();
    }

    private static Optional<WeaponChoice.Picked> pick(LocalPlayer self) {
        List<WeaponChoice.Carried> carried = WeaponCarriedReader.read(self);
        return WeaponChoice.pickRanged(carried, WeaponCarriedReader.hasArrows(self));
    }

    @Override
    public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        current = player;
        if (modShot != null) return modShot.tick(context);
        if (failure != null) return ActionStatus.failed(failure);
        if (stage == Stage.DONE) return ActionStatus.done();
        if (player == null || player.localPlayer() == null || player.interactionSender() == null) {
            return ActionStatus.running();
        }
        Entity target = player.level() == null ? null : player.level().getEntity(targetId);
        if (target == null || !target.isAlive()) {
            cancelDraw(player);
            stage = Stage.DONE;
            return ActionStatus.done();
        }
        if (pending != null && !pending.terminal()) {
            pending = player.interactionSender().poll(player, pending);
            if (!pending.terminal()) {
                renew(player);
                return ActionStatus.running();
            }
        }
        return switch (stage) {
            case ARM -> arm(context, player, target);
            case START -> start(player);
            case DRAW -> draw(player, target);
            case LOAD -> load(player);
            case LOADING_RELEASE -> loaded(player);
            case AIM -> aimCrossbow(player, target);
            case FIRING -> settleFire(player);
            case DONE -> ActionStatus.done();
        };
    }

    // 拿到手上：上了弦的弩先；没有就看联动的远程武器（点一下就发，排在弓之前），挑中了整发交给它；
    // 再是弓、有箭可装的弩。已经在主手就不动，换不成如实失败。
    private ActionStatus arm(TickContext context, PlayerContext player, Entity target) {
        LocalPlayer self = player.localPlayer();
        if (arming == null) {
            Optional<WeaponChoice.Picked> weapon = pick(self);
            boolean loadedCrossbow = weapon.filter(picked -> picked.weapon() == WeaponChoice.Weapon.LOADED_CROSSBOW).isPresent();
            Optional<ModRangedWeapons.Ready> modded = loadedCrossbow ? Optional.empty()
                    : weapons.ready(self, self.distanceTo(target));
            if (modded.isPresent()) {
                modShot = new ModShot(targetId, weapons, modded.get());
                return modShot.tick(context);
            }
            if (weapon.isEmpty()) return fail("身上没有能射的：没有弓和箭，也没有弩");
            crossbow = weapon.get().weapon() != WeaponChoice.Weapon.READY_BOW;
            if (weapon.get().slot() != self.getInventory().selected) {
                // 弓弩在主背包就换进快捷栏的空格再选中，不挤掉手里的镐；在快捷栏就按数字键切过去。
                arming = hands.actionToMainhand(weapon.get().itemId()).orElse(null);
                if (arming == null) return fail("没能把" + weapon.get().itemId() + "拿到手上");
            }
        }
        if (arming != null) {
            ActionStatus status = arming.tick(context);
            if (status instanceof ActionStatus.Running) return status;
            if (status instanceof ActionStatus.Failed failed) return fail("换弓弩没成：" + failed.problem().message());
        }
        ItemStack hand = self.getMainHandItem();
        if (!(hand.getItem() instanceof BowItem || hand.getItem() instanceof CrossbowItem)) {
            return fail("主手不是弓也不是弩，射不了");
        }
        crossbow = hand.getItem() instanceof CrossbowItem;
        stage = crossbow && CrossbowItem.isCharged(hand) ? Stage.AIM : Stage.START;
        return ActionStatus.progressed();
    }

    // 起手：右键开始使用（拉弓或装弩），确认真的在用了才往下走。
    private ActionStatus start(PlayerContext player) {
        if (pending == null) {
            if (!player.canInteractThisTick()) return ActionStatus.running();
            heldAtStart = player.localPlayer().getMainHandItem().copy();
            pending = player.interactionSender().useItem(player, InteractionHand.MAIN_HAND,
                    c -> c.localPlayer().isUsingItem() ? InteractionConfirmation.Verdict.APPLIED
                            : InteractionConfirmation.Verdict.PENDING, CONFIRM_TICKS);
            renew(player);
            return ActionStatus.progressed();
        }
        if (pending.status() != PendingInteraction.Status.CONFIRMED_APPLIED) {
            return fail("拉弓（装弩）没开始：" + pending.detail());
        }
        stage = crossbow ? Stage.LOAD : Stage.DRAW;
        return ActionStatus.progressed();
    }

    // 拉弓：按住到拉满；快放前几刻转向弹道，对准了就松手放箭。等不到弹道拉太久就取消（切槽，不放）。
    private ActionStatus draw(PlayerContext player, Entity target) {
        LocalPlayer self = player.localPlayer();
        if (!self.isUsingItem()) return fail("弓被松开了，没射出去");
        renew(player);
        held = self.getTicksUsingItem();
        if (held < FULL_DRAW_TICKS - AIM_LEAD_TICKS) return ActionStatus.progressed();
        Optional<ArrowAim.Aim> aim = ArrowAim.find(player.level(), self, target,
                ArrowAim.bowSpeed(Math.max(held, FULL_DRAW_TICKS)), true, maxRange);
        if (aim.isEmpty()) {
            if (held >= MAX_DRAW_TICKS) {
                cancelDraw(player);
                stage = Stage.DONE;
            }
            return ActionStatus.running();
        }
        player.input().lookAt(self, aim.get().lookPoint());
        boolean onTarget = ArrowAim.angleDegrees(self.getViewVector(1.0f), aim.get().direction()) <= AIM_TOLERANCE_DEGREES;
        if (held < FULL_DRAW_TICKS || !onTarget || !player.canInteractThisTick()) return ActionStatus.progressed();
        // 有意松手前先交还按住的投影，免得放完箭原版又起手拉下一箭。
        projection.release(this);
        pending = player.interactionSender().releaseUsingItem(player, pending);
        stage = Stage.FIRING;
        return ActionStatus.progressed();
    }

    // 装弩：按住到装填时间，松手；松开确认、弩上了弦才去瞄。
    private ActionStatus load(PlayerContext player) {
        LocalPlayer self = player.localPlayer();
        if (!self.isUsingItem()) {
            if (CrossbowItem.isCharged(self.getMainHandItem())) {
                stage = Stage.AIM;
                return ActionStatus.progressed();
            }
            return fail("装弩被打断了，没装上");
        }
        renew(player);
        held = self.getTicksUsingItem();
        if (held >= CrossbowItem.getChargeDuration(self.getMainHandItem(), self) && player.canInteractThisTick()) {
            projection.release(this);
            pending = player.interactionSender().releaseUsingItem(player, pending);
            stage = Stage.LOADING_RELEASE;
            return ActionStatus.progressed();
        }
        if (held >= LOAD_TIMEOUT_TICKS) return fail("弩装了 " + held + " 刻还没装上");
        return ActionStatus.progressed();
    }

    private ActionStatus loaded(PlayerContext player) {
        if (!CrossbowItem.isCharged(player.localPlayer().getMainHandItem())) {
            return fail("松开后弩没有上弦：" + (pending == null ? "" : pending.detail()));
        }
        pending = null;
        stage = Stage.AIM;
        return ActionStatus.progressed();
    }

    // 弩上了弦：转向弹道，对准了点一下射出去；等不到弹道太久就先留着弦（不浪费），这一下算没射。
    private ActionStatus aimCrossbow(PlayerContext player, Entity target) {
        LocalPlayer self = player.localPlayer();
        ItemStack hand = self.getMainHandItem();
        if (!CrossbowItem.isCharged(hand)) return fail("弩没有上弦");
        Optional<ArrowAim.Aim> aim = ArrowAim.find(player.level(), self, target, ArrowAim.CROSSBOW_SPEED, false, maxRange);
        if (aim.isEmpty()) {
            if (++aimWaited >= MAX_AIM_TICKS) stage = Stage.DONE;
            return ActionStatus.running();
        }
        player.input().lookAt(self, aim.get().lookPoint());
        if (ArrowAim.angleDegrees(self.getViewVector(1.0f), aim.get().direction()) > AIM_TOLERANCE_DEGREES
                || !player.canInteractThisTick()) {
            return ActionStatus.progressed();
        }
        ItemStack before = hand.copy();
        pending = player.interactionSender().useItem(player, InteractionHand.MAIN_HAND,
                InteractionConfirmation.heldItemChanged(InteractionHand.MAIN_HAND, before), CONFIRM_TICKS);
        stage = Stage.FIRING;
        return ActionStatus.progressed();
    }

    private ActionStatus settleFire(PlayerContext player) {
        if (pending != null && pending.status() == PendingInteraction.Status.CONFIRMED_APPLIED) {
            fired = true;
            stage = Stage.DONE;
            return ActionStatus.done();
        }
        return fail("放箭没得到游戏确认：" + (pending == null ? "" : pending.detail()));
    }

    // 按住期间每刻续上使用键的投影：断了原版就当松手（弓会放箭）。
    private void renew(PlayerContext player) {
        if ((stage == Stage.START || stage == Stage.DRAW || stage == Stage.LOAD) && pending != null) {
            projection.renew(this, player, pending, InteractionHand.MAIN_HAND, heldAtStart);
        }
    }

    // 取消拉弓、装弩：切一下快捷栏让原版停止使用，不松手（松开弓就是放箭）；本刻没有交互机会就只交还投影。
    private void cancelDraw(PlayerContext player) {
        projection.release(this);
        if (player == null || pending == null || !player.localPlayer().isUsingItem()
                || player.localPlayer().getUsedItemHand() != InteractionHand.MAIN_HAND
                || !player.canInteractThisTick()) {
            return;
        }
        try {
            pending = player.interactionSender().cancelMainHandUse(player, pending);
        } catch (RuntimeException busy) {
            // 交互入口此刻不收：投影已交还，原版两刻内松开；弓可能因此放出一箭，如实留在世界里。
        }
    }

    private ActionStatus fail(String reason) {
        projection.release(this);
        failure = Problem.of(Problem.Kind.REFUSED_BY_GAME, reason);
        stage = Stage.DONE;
        return ActionStatus.failed(failure);
    }

    @Override
    public void pause() {
        if (modShot != null) {
            modShot.pause();
            return;
        }
        if (arming != null) arming.pause();
        cancelDraw(current);
    }

    @Override
    public void close() {
        if (modShot != null) {
            modShot.close();
            return;
        }
        if (arming != null) arming.close();
        cancelDraw(current);
    }

    @Override
    public Interruptibility interruptibility() {
        if (modShot != null) return modShot.interruptibility();
        return stage == Stage.FIRING || stage == Stage.LOADING_RELEASE ? Interruptibility.UNSAFE_TO_STOP
                : Interruptibility.WORKING;
    }

    @Override
    public String describe() {
        if (modShot != null) return modShot.describe();
        return switch (stage) {
            case ARM -> "把弓弩拿到手上";
            case START, DRAW -> "拉弓（" + held + " 刻）";
            case LOAD, LOADING_RELEASE -> "装弩";
            case AIM -> "端着弩瞄准";
            case FIRING -> "放箭";
            case DONE -> fired ? "射出一箭" : "这一箭没射";
        };
    }
}
