package org.maiwithu.maicraft.core.task.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.act.Ballistics;
import org.maiwithu.maicraft.core.combat.Loadout;

/** One bow/crossbow shot, submitted and reconciled through the native first-person port. */
final class RangedShot {
    private static final int BOW_RELEASE_TICKS = 15;
    private static final int BOW_MAX_DRAW_TICKS = 40;
    private static final int CROSSBOW_LOAD_TIMEOUT = 80;
    static final double AIM_THRESHOLD_DEGREES = 1.5;

    private enum State { STARTING, USING, READY_TO_FIRE, RELEASING_LOAD, FIRING, DONE, MISFIRE }
    private final LocalPlayer player;
    private final boolean crossbow;
    private State state = State.STARTING;
    private NativeActionReceipt receipt;
    private int held;
    private boolean fired;
    private Ballistics.Aim pendingAim;
    private Entity pendingTarget;

    RangedShot(LocalPlayer player, boolean crossbow) {
        this.player = player;
        this.crossbow = crossbow;
    }

    static boolean stillHolding(boolean crossbow, ItemStack stack) {
        return crossbow ? stack.getItem() instanceof CrossbowItem : stack.getItem() instanceof BowItem;
    }
    static double bowPowerForTicks(int ticks) {
        double draw = ticks / 20.0;
        return Math.min(1.0, Math.max(0.0, (draw * draw + draw * 2.0) / 3.0));
    }
    static boolean canRelease(double angleDegrees, int heldTicks, int releaseTicks) {
        return angleDegrees <= AIM_THRESHOLD_DEGREES && heldTicks >= releaseTicks;
    }
    double projectileVelocity(double bowFullSpeed, double crossbowSpeed) {
        return crossbow ? crossbowSpeed : bowFullSpeed * bowPowerForTicks(Math.max(BOW_RELEASE_TICKS, held + 1));
    }

    boolean tick(Ballistics.Aim aim, Entity target) {
        switch (state) {
            case STARTING -> startUse();
            case USING -> tickUsing(aim, target);
            case READY_TO_FIRE -> tickReady(aim, target);
            case RELEASING_LOAD -> settleLoadRelease();
            case FIRING -> settleFire();
            default -> { }
        }
        return state == State.DONE || state == State.MISFIRE;
    }

    boolean fired() { return fired; }
    boolean aboutToRelease() { return crossbow ? state == State.READY_TO_FIRE : held >= BOW_RELEASE_TICKS - 1; }

    void abort() {
        if (receipt == null || receipt.kind() != NativeActionReceipt.Kind.USE_ITEM || !player.isUsingItem()) return;
        var context = ClientRuntime.requireContext(player);
        receipt = context.actions().releaseUsingItem(context, receipt);
        state = State.MISFIRE;
    }

    private void startUse() {
        var context = ClientRuntime.requireContext(player);
        if (receipt == null) {
            ItemStack before = player.getMainHandItem().copy();
            receipt = context.actions().useItem(context, InteractionHand.MAIN_HAND,
                    NativeConfirmation.anyOf(
                            NativeConfirmation.heldItemChanged(InteractionHand.MAIN_HAND, before),
                            c -> c.player().isUsingItem() || CrossbowItem.isCharged(c.player().getMainHandItem())
                                    ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING),
                    20);
            return;
        }
        receipt = context.actions().poll(context, receipt);
        if (!receipt.terminal()) return;
        if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            state = State.MISFIRE; return;
        }
        state = crossbow && CrossbowItem.isCharged(player.getMainHandItem())
                ? State.READY_TO_FIRE : State.USING;
    }

    private void tickUsing(Ballistics.Aim aim, Entity target) {
        if (!player.isUsingItem()) { state = State.MISFIRE; return; }
        held++;
        if (crossbow) {
            if (held >= CrossbowItem.getChargeDuration(player.getMainHandItem(), player)) {
                releaseLoad();
            } else if (held >= CROSSBOW_LOAD_TIMEOUT) state = State.MISFIRE;
            return;
        }
        double angle = Ballistics.angleDegrees(player.getViewVector(1.0f), aim.direction());
        if (canRelease(angle, held, BOW_RELEASE_TICKS) || held >= BOW_MAX_DRAW_TICKS) {
            pendingAim = aim; pendingTarget = target;
            var context = ClientRuntime.requireContext(player);
            receipt = context.actions().releaseUsingItem(context, receipt);
            state = State.FIRING;
        }
    }

    private void releaseLoad() {
        var context = ClientRuntime.requireContext(player);
        receipt = context.actions().releaseUsingItem(context, receipt);
        state = State.RELEASING_LOAD;
    }

    private void settleLoadRelease() {
        var context = ClientRuntime.requireContext(player);
        receipt = context.actions().poll(context, receipt);
        if (!receipt.terminal()) return;
        state = receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED
                && CrossbowItem.isCharged(player.getMainHandItem()) ? State.READY_TO_FIRE : State.MISFIRE;
        receipt = null;
    }

    private void tickReady(Ballistics.Aim aim, Entity target) {
        if (Ballistics.angleDegrees(player.getViewVector(1.0f), aim.direction()) > AIM_THRESHOLD_DEGREES) return;
        var context = ClientRuntime.requireContext(player);
        if (receipt == null) {
            ItemStack before = player.getMainHandItem().copy();
            pendingAim = aim; pendingTarget = target;
            receipt = context.actions().useItem(context, InteractionHand.MAIN_HAND,
                    NativeConfirmation.heldItemChanged(InteractionHand.MAIN_HAND, before), 20);
            state = State.FIRING;
        }
    }

    private void settleFire() {
        var context = ClientRuntime.requireContext(player);
        receipt = context.actions().poll(context, receipt);
        if (!receipt.terminal()) return;
        if (receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED) markFired();
        else state = State.MISFIRE;
    }

    private void markFired() {
        fired = true;
        if (pendingTarget != null) {
            Constants.LOG.info("[maicraft-attack] 射出 target={} weapon={} held={} dist={} eta={}",
                    pendingTarget.getId(), crossbow ? "crossbow" : "bow", held,
                    String.format("%.1f", player.distanceTo(pendingTarget)),
                    pendingAim == null ? "?" : Math.ceil(pendingAim.travelTicks()));
        }
        state = State.DONE;
    }

    static boolean isCrossbow(Loadout.Pick pick) { return pick.stack().getItem() instanceof CrossbowItem; }
}
