package org.maiwithu.maicraft.client.actor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** 只延续任务已经开始的那一次原生持用；不改真实按键、不重复右键，也不替游戏完成吃饭。 */
public final class ItemUseInputLease {
    private static Lease active;
    private record Lease(Object owner, Minecraft minecraft, LocalPlayer player, Object level, BodyControlPort body,
                         DefaultNativeActionPort actions, NativeActionReceipt receipt, InteractionHand hand,
                         ItemStack item, int selected, int remaining, long throughTick, long throughNanos) {}
    private ItemUseInputLease() {}

    public static boolean renew(Object owner, LocalPlayerContext context, NativeActionReceipt receipt,
                                InteractionHand hand, ItemStack before) {
        if (!context.isCurrent() || !context.permitsNativeActions() || !context.body().automationOwnsControls()
                || context.minecraft().screen != null || !(context.actions() instanceof DefaultNativeActionPort actions)
                || !actions.ownsItemUse(receipt) || receipt.bodyEpoch() != context.bodyEpoch()
                || receipt.controlRevision() != context.controlRevision()) return false;
        if (active != null && active.owner != owner && valid(active)) return false;
        int selected = active != null && active.owner == owner && active.receipt == receipt
                ? active.selected : context.player().getInventory().selected;
        int remaining = context.player().getUseItemRemainingTicks();
        if (active != null && active.owner == owner && active.receipt == receipt) remaining = Math.min(remaining, active.remaining);
        active = new Lease(owner, context.minecraft(), context.player(), context.level(), context.body(), actions,
                receipt, hand, before.copy(), selected, remaining,
                context.level().getGameTime() + 2, System.nanoTime() + 250_000_000L);
        return true;
    }

    /** 原版只在正在使用同一物品时获得按住投影；吃完后返回真实键值，不触发下一块面包。 */
    public static boolean project(Minecraft minecraft, boolean actual) {
        Lease lease = active;
        if (lease == null || lease.minecraft != minecraft) return actual;
        if (!valid(lease)) { active = null; return actual; }
        return actual || sameUse(lease);
    }

    /** 停止动作也必须仍拥有同一回执和手中物品，不能用旧吃饭任务松掉后来开始的持用。 */
    public static boolean owns(Object owner, LocalPlayerContext context, NativeActionReceipt receipt) {
        return active != null && active.owner == owner && active.receipt == receipt
                && active.player == context.player() && context.isCurrent() && valid(active) && sameUse(active);
    }
    public static void release(Object owner) { if (active != null && active.owner == owner) active = null; }
    static void revoke(DefaultNativeActionPort actions) { if (active != null && active.actions == actions) active = null; }

    private static boolean valid(Lease lease) {
        return lease.minecraft.player == lease.player && lease.minecraft.level == lease.level
                && lease.minecraft.screen == null && lease.body.automationOwnsControls()
                && lease.actions.ownsItemUse(lease.receipt)
                && lease.player.level().getGameTime() <= lease.throughTick && System.nanoTime() <= lease.throughNanos;
    }
    private static boolean sameUse(Lease lease) {
        return lease.player.isUsingItem() && lease.player.getUsedItemHand() == lease.hand
                && lease.player.getUseItemRemainingTicks() <= lease.remaining
                && (lease.hand != InteractionHand.MAIN_HAND || lease.player.getInventory().selected == lease.selected)
                && same(lease.item, lease.player.getItemInHand(lease.hand)) && same(lease.item, lease.player.getUseItem());
    }
    private static boolean same(ItemStack left, ItemStack right) {
        // 食物扣数与结束标志可能分批同步；同一倒计时内允许扣数，重新开始的倒计时不能继承旧持用。
        return ItemStack.isSameItemSameComponents(left, right);
    }
}
