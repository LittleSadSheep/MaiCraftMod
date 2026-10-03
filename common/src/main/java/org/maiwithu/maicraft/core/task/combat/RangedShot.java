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
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.DefaultNativeActionPort;
import org.maiwithu.maicraft.client.actor.HeldUseItems;
import org.maiwithu.maicraft.client.actor.ItemUseInputLease;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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
    private ItemStack heldUseBefore = ItemStack.EMPTY;
    private int selectedSlot = -1;
    private String failure = "";

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
        // 蓄力可能在等弹道期间继续增长；弹速和松手检查使用同一份原生持用刻数，不按调用次数猜测。
        return crossbow ? crossbowSpeed : bowFullSpeed * bowPowerForTicks(Math.max(BOW_RELEASE_TICKS, held));
    }

    // 一次只推进当前阶段。返回 true 表示这一轮已经结束，是否真的按这里规则判为发射还要看 fired。
    boolean tick(Ballistics.Aim aim, Entity target) {
        maintainUse(); // 与进食、举盾一样续订已开始的原生持用，不重复右键。
        switch (state) {
            case STARTING -> startUse();
            case USING -> tickUsing(aim, target);
            case READY_TO_FIRE -> tickReady(aim, target);
            case RELEASING_LOAD -> settleLoadRelease();
            case FIRING -> settleFire();
            default -> { }
        }
        boolean finished = state == State.DONE || state == State.MISFIRE;
        if (finished) ItemUseInputLease.release(this);
        return finished;
    }

    void maintainUse() {
        // 此步不消耗操作名额；射程外、暂时无弹道或本刻已有操作时，仍保持同一把弓弩的使用键。
        if ((state != State.STARTING && state != State.USING) || receipt == null || !player.isUsingItem()) return;
        LocalPlayerContext context = ClientRuntime.actor().activeContext().filter(c -> c.player() == player && c.isCurrent()).orElse(null);
        if (context == null || !(context.actions() instanceof DefaultNativeActionPort actions) || !actions.ownsItemUse(receipt)) return;
        ItemUseInputLease.renew(this, context, receipt, InteractionHand.MAIN_HAND, heldUseBefore);
        if (ItemUseInputLease.owns(this, context, receipt)) held = player.getTicksUsingItem();
    }

    boolean fired() { return fired; }
    boolean aboutToRelease() { return crossbow ? state == State.READY_TO_FIRE : held >= BOW_RELEASE_TICKS - 1; }

    // 取消蓄力用切槽；松开弓会真的发射，不能用作取消。已经发出的箭不能撤回。
    void abort() {
        if (receipt != null && receipt.kind() == NativeActionReceipt.Kind.USE_ITEM) {
            // 清理只执行一次；本刻无名额时把持用租约和取消责任一并交给原生端口，跨过下刻松手检查再切槽。
            LocalPlayerContext context = ClientRuntime.actor()
                    .activeContext().filter(c -> c.player() == player).orElse(null);
            // 旧射击流程不能取消后来者的持用。
            if (context != null && context.permitsNativeActions() && context.body().automationOwnsControls()
                    && context.actions() instanceof DefaultNativeActionPort actions
                    && actions.ownsItemUse(receipt) && player.getInventory().selected == selectedSlot
                    && HeldUseItems.same(heldUseBefore, player.getMainHandItem(), player::registryAccess)
                    && (!player.isUsingItem() || player.getUsedItemHand() == InteractionHand.MAIN_HAND
                    && HeldUseItems.same(heldUseBefore, player.getUseItem(), player::registryAccess))) {
                if (context.mutationAvailable()) receipt = actions.cancelMainHandUse(context, receipt);
                else actions.deferMainHandUseCancellation(this, context, receipt);
            }
        }
        misfire(failure.isEmpty() ? "cancelled" : failure);
    }

    // 只有尚未装填的弩和弓需要开始使用；已装填弩由构造器直接送去瞄准阶段。
    private void startUse() {
        var context = ClientRuntime.requireContext(player);
        if (receipt == null) {
            ItemStack before = player.getMainHandItem().copy();
            heldUseBefore = before; selectedSlot = player.getInventory().selected;
            receipt = context.actions().useItem(context, InteractionHand.MAIN_HAND,
                    NativeConfirmation.anyOf(
                            NativeConfirmation.heldItemChanged(InteractionHand.MAIN_HAND, before),
                            c -> c.player().isUsingItem() || CrossbowItem.isCharged(c.player().getMainHandItem())
                                    ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING),
                    20);
            // 绑定原生起手后实际持用的组件，随后换手、换物或控制权变化仍会撤销这份租约。
            if (player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND
                    && ItemStack.isSameItem(before, player.getUseItem())
                    && ItemStack.isSameItemSameComponents(player.getUseItem(), player.getMainHandItem()))
                heldUseBefore = player.getUseItem().copy();
            maintainUse();
            return;
        }
        receipt = context.actions().poll(context, receipt);
        if (!receipt.terminal()) return;
        if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            misfire("start_use_not_confirmed"); return;
        }
        if (crossbow && CrossbowItem.isCharged(player.getMainHandItem())) {
            if (player.isUsingItem()) releaseLoad();
            else { receipt = null; state = State.READY_TO_FIRE; }
        } else state = State.USING;
    }

    // 使用状态中断就结束为失败。弩等装填时间到后松开；弓至少拉十五刻并尽量等准星对齐。
    private void tickUsing(Ballistics.Aim aim, Entity target) {
        if (!player.isUsingItem()) { misfire("native_use_interrupted"); return; }
        var context = ClientRuntime.requireContext(player);
        if (!ItemUseInputLease.owns(this, context, receipt)) { misfire("held_use_ownership_changed"); return; }
        held = player.getTicksUsingItem(); // 沿用原有蓄力阈值，但以原版倒计时为准，避免回执等待或弹道等待造成计时漂移。
        if (crossbow) {
            if (held >= CrossbowItem.getChargeDuration(player.getMainHandItem(), player)) {
                releaseLoad();
            } else if (held >= CROSSBOW_LOAD_TIMEOUT) misfire("crossbow_load_timeout");
            return;
        }
        double angle = Ballistics.angleDegrees(player.getViewVector(1.0f), aim.direction());
        if (canRelease(angle, held, BOW_RELEASE_TICKS)) {
            pendingAim = aim; pendingTarget = target;
            ItemUseInputLease.release(this); // 有意松手前撤销按住投影，避免发射后原版再起一箭。
            receipt = context.actions().releaseUsingItem(context, receipt);
            state = State.FIRING;
        } else if (held >= BOW_MAX_DRAW_TICKS) { failure = "draw_aim_timeout"; abort(); }
    }

    private void releaseLoad() {
        var context = ClientRuntime.requireContext(player);
        ItemUseInputLease.release(this); // 装填松手与真正发射分开，松手后不再续旧持用。
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
        if (state == State.MISFIRE) failure = "crossbow_load_not_confirmed";
        receipt = null;
    }

    // 已经装填好时等实际视线接近弹道方向，再右键发射。这里还要求旧 receipt 已清空。
    private void tickReady(Ballistics.Aim aim, Entity target) {
        if (!CrossbowItem.isCharged(player.getMainHandItem())) { misfire("crossbow_not_charged"); return; }
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
        else misfire("launch_action_not_confirmed");
    }

    private void misfire(String reason) {
        // 失败只释放自己的按住投影，保留原生回执的状态和细节，不把准备动作或蓄力时间当作已射箭。
        failure = reason; state = State.MISFIRE; ItemUseInputLease.release(this);
    }

    Map<String, Object> evidence() {
        // 回执供实机区分起手、蓄力、装填和发射确认；该范围不等于观察到箭实体或目标命中。
        var data = new LinkedHashMap<String, Object>();
        data.put("state", state.name().toLowerCase(Locale.ROOT)); data.put("weapon", crossbow ? "crossbow" : "bow");
        data.put("native_use_ticks", held); data.put("launch_action_confirmed", fired);
        data.put("confirmation_scope", "native_use_or_release_receipt_not_projectile_hit");
        if (!failure.isEmpty()) data.put("failure", failure);
        if (receipt != null) {
            data.put("receipt_status", receipt.status().name().toLowerCase(Locale.ROOT));
            if (receipt.detail() != null) data.put("receipt_detail", receipt.detail());
        }
        return Map.copyOf(data);
    }

    private void markFired() {
        fired = true;
        if (pendingTarget != null) {
            Constants.LOG.info("[maicraft-attack] 发射动作已确认 target={} weapon={} held={} dist={} eta={}",
                    pendingTarget.getId(), crossbow ? "crossbow" : "bow", held,
                    String.format("%.1f", player.distanceTo(pendingTarget)),
                    pendingAim == null ? "?" : Math.ceil(pendingAim.travelTicks()));
        }
        state = State.DONE;
    }

    static boolean isCrossbow(Loadout.Pick pick) { return pick.stack().getItem() instanceof CrossbowItem; }
}
