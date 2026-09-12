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

/**
 * 把一发弓箭或弩箭分成跨刻操作：选好的武器开始使用，等待拉弓／装填，瞄准后松开或再次点击。
 * 本对象记住阶段和动作记录；走到射程内、选择武器、提供预测瞄点都由外层战斗任务负责。
 */
final class RangedShot {
    private static final int BOW_RELEASE_TICKS = 15;
    private static final int BOW_MAX_DRAW_TICKS = 40;
    private static final int CROSSBOW_LOAD_TIMEOUT = 80;
    static final double AIM_THRESHOLD_DEGREES = 1.5;

    // 按阶段记住：开始使用、拉弓／装弩、准备发射、松开装填、等待发射结果，最后成功或失败。
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
        if (crossbow && CrossbowItem.isCharged(player.getMainHandItem())) state = State.READY_TO_FIRE;
    }

    static boolean stillHolding(boolean crossbow, ItemStack stack) {
        return crossbow ? stack.getItem() instanceof CrossbowItem : stack.getItem() instanceof BowItem;
    }
    // 按拉弓时间估计箭速比例，最多满弓；这里算比例，不代表已经装好箭或射出去。
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

    // 一次只推进当前阶段。返回 true 表示这一轮已经结束，是否真的按这里规则判为发射还要看 fired。
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

    // 取消蓄力用切槽；松开弓会真的发射，不能用作取消。已经发出的箭不能撤回。
    void abort() {
        if (receipt != null && receipt.kind() == NativeActionReceipt.Kind.USE_ITEM) {
            var context = ClientRuntime.requireContext(player);
            receipt = context.actions().cancelMainHandUse(context, receipt);
        }
        state = State.MISFIRE;
    }

    // 只有尚未装填的弩和弓需要开始使用；已装填弩由构造器直接送去瞄准阶段。
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
        if (crossbow && CrossbowItem.isCharged(player.getMainHandItem())) {
            if (player.isUsingItem()) releaseLoad();
            else { receipt = null; state = State.READY_TO_FIRE; }
        } else state = State.USING;
    }

    // 使用状态中断就结束为失败。弩等装填时间到后松开；弓至少拉十五刻并尽量等准星对齐。
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
        if (canRelease(angle, held, BOW_RELEASE_TICKS)) {
            pendingAim = aim; pendingTarget = target;
            var context = ClientRuntime.requireContext(player);
            receipt = context.actions().releaseUsingItem(context, receipt);
            state = State.FIRING;
        } else if (held >= BOW_MAX_DRAW_TICKS) abort();
    }

    private void releaseLoad() {
        var context = ClientRuntime.requireContext(player);
        receipt = context.actions().releaseUsingItem(context, receipt);
        state = State.RELEASING_LOAD;
    }

    // 松开装填得到确认且弩确实显示已装填，才进入待发射；同时清掉旧记录，允许下一次右键。
    private void settleLoadRelease() {
        var context = ClientRuntime.requireContext(player);
        receipt = context.actions().poll(context, receipt);
        if (!receipt.terminal()) return;
        state = receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED
                && CrossbowItem.isCharged(player.getMainHandItem()) ? State.READY_TO_FIRE : State.MISFIRE;
        receipt = null;
    }

    // 已经装填好时等实际视线接近弹道方向，再右键发射。这里还要求旧 receipt 已清空。
    private void tickReady(Ballistics.Aim aim, Entity target) {
        if (!CrossbowItem.isCharged(player.getMainHandItem())) { state = State.MISFIRE; return; }
        if (!canRelease(Ballistics.angleDegrees(player.getViewVector(1.0f), aim.direction()), 0, 0)) return;
        var context = ClientRuntime.requireContext(player);
        if (receipt == null) {
            ItemStack before = player.getMainHandItem().copy();
            pendingAim = aim; pendingTarget = target;
            receipt = context.actions().useItem(context, InteractionHand.MAIN_HAND,
                    NativeConfirmation.heldItemChanged(InteractionHand.MAIN_HAND, before), 20);
            state = State.FIRING;
        }
    }

    // 等待公共动作接口的结果；弓此时等的是“使用已停止”，弩等的是手中物品改变。
    // 这些条件没有直接观察箭生成，更没有确认命中；上层不能把 fired 当成击中或击败证据。
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
