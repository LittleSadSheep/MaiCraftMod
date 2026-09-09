package org.maiwithu.maicraft.core.pathing.baritone.landing;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;

/**
 * 把落地用品准备到手里：能用现成手持时直接用，需要取背包物品时先打开自己的库存、移到快捷栏并关好界面。
 * 普通准备要求站稳；紧急准备可在空中尝试，但时间不足时只考虑更快的现成手持或快捷栏。
 */
public final class LandingPreparation {
    private final Item item;
    private final VisibleMenuSession menus = new VisibleMenuSession();
    private MenuReceipt swap;
    private MenuReceipt interruptedClose;
    private NativeActionReceipt selection;
    private boolean failed, ready, inventoryTouched, interrupted;
    private boolean airborneAllowed;
    private long startedTick = Long.MIN_VALUE;
    private String detail = "preparing carried landing item on stable ground";
    private InteractionHand hand = InteractionHand.MAIN_HAND;

    public LandingPreparation(Item item) { this.item = item; }
    /** Fast path for a held item; preparation and hotbar changes use the tick methods. */
    public boolean acceptHeld(LocalPlayerContext context) {
        if (selection != null || swap != null || inventoryTouched || interruptedClose != null || failed) return false;
        if (!context.permitsNativeActions()
                || !org.maiwithu.maicraft.client.actor.DefaultBodyControlPort.permitsWorldMovement(context.minecraft().screen)) return false;
        if (context.player().getMainHandItem().is(item)) hand = InteractionHand.MAIN_HAND;
        else if (context.player().getOffhandItem().is(item)) hand = InteractionHand.OFF_HAND;
        else return false;
        ready = true; detail = "landing item already held; no airborne inventory changes"; return true;
    }
    public boolean tick(LocalPlayerContext context) {
        if (failed) return false;
        if (ready && context.player().getItemInHand(hand).is(item)) return true;
        // Preparation is rechecked on each supported departure tick. A stale ready flag must
        // not let a changed hotbar selection send the player off the edge without the bucket.
        ready = false;
        if (!context.permitsNativeActions()) return false;
        interrupted |= !airborneAllowed && !context.player().onGround();
        try {
            if (startedTick == Long.MIN_VALUE) startedTick = context.tickRevision();
            if (context.tickRevision() - startedTick > 200) return fail("landing item preparation exceeded its bounded window");
            if (interrupted) return closeInterrupted(context);
            if (selection != null) {
                selection = context.actions().poll(context, selection);
                if (!selection.terminal()) return false;
                if (selection.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) return fail(selection.detail());
                selection = null;
            }
            if (swap != null) {
                swap = context.menus().poll(context, swap);
                if (!swap.terminal()) return false;
                if (swap.status() != MenuReceipt.Status.CONFIRMED_APPLIED) return fail(swap.detail());
                swap = null;
            }
            if (context.player().getOffhandItem().is(item)) hand = InteractionHand.OFF_HAND;
            else {
                int slot = -1;
                for (int i = 0; i < 36; i++) if (context.player().getInventory().getItem(i).is(item)) { slot = i; break; }
                if (slot < 0) return fail("required landing item is no longer carried");
                if (slot >= 9) {
                    if (!context.mutationAvailable()) return false;
                    inventoryTouched = true;
                    if (!menus.inventoryReady(context)) return false;
                    swap = context.menus().swapInventoryToHotbar(context, slot,
                            context.player().getInventory().selected, 20);
                    return false;
                }
                if (!menus.close(context) || !menus.worldReady(context)) return false;
                if (context.player().getInventory().selected != slot) {
                    if (context.mutationAvailable()) selection = context.actions().selectHotbar(context, slot, 10);
                    return false;
                }
            }
            if (!menus.close(context) || !menus.worldReady(context)) return false;
            ready = true;
            detail = "carried landing item selected and inventory closure confirmed";
            return true;
        } catch (RuntimeException unavailable) {
            return fail("landing preparation failed: " + unavailable.getMessage());
        } finally {
            if (failed && inventoryTouched && interruptedClose == null) {
                try { beginClosure(context); }
                catch (RuntimeException unavailable) { detail += "; owned inventory closure could not be submitted"; }
            }
        }
    }
    /** A carried inventory item remains first choice in flight; use native visible inventory actions. */
    public boolean tickEmergency(LocalPlayerContext context) {
        return tickEmergency(context,Integer.MAX_VALUE);
    }
    public boolean tickEmergency(LocalPlayerContext context, int remainingTicks) {
        airborneAllowed = true;
        if (remainingTicks <= 3 && !ready) {
            if (inventoryTouched) { closeForFailure(context); return false; }
            boolean quick = context.player().getMainHandItem().is(item) || context.player().getOffhandItem().is(item)
                    || java.util.stream.IntStream.range(0,9).anyMatch(slot -> context.player().getInventory().getItem(slot).is(item));
            if (!quick) return fail("carried inventory transfer cannot finish before the landing action window");
        }
        // 当前只要普通背包也有同物品就走完整准备；它会跳过后面的现成手持判断，可能反而重选另一格同物品。
        if (inventoryTouched || java.util.stream.IntStream.range(9,36).anyMatch(slot ->
                context.player().getInventory().getItem(slot).is(item))) return tick(context);
        if (failed) return false;
        try {
            if (selection != null) {
                selection = context.actions().poll(context, selection);
                if (!selection.terminal()) return false;
                if (selection.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) return fail(selection.detail());
                selection = null;
            }
            if (acceptHeld(context)) return true;
            if (!context.permitsNativeActions()
                    || !org.maiwithu.maicraft.client.actor.DefaultBodyControlPort.permitsWorldMovement(context.minecraft().screen))
                return fail("emergency item selection lost world control");
            for (int slot = 0; slot < 9; slot++) {
                if (!context.player().getInventory().getItem(slot).is(item)) continue;
                if (context.mutationAvailable()) selection = context.actions().selectHotbar(context, slot, 10);
                return false;
            }
            return fail("emergency landing item is not in either hand or the hotbar");
        } catch (RuntimeException unavailable) { return fail("emergency selection failed: " + unavailable.getMessage()); }
    }
    public void closeForFailure(LocalPlayerContext context) {
        interrupted = true;
        if (context.permitsNativeActions()) closeInterrupted(context);
    }
    public boolean cleanupPending() { return interruptedClose != null && !interruptedClose.terminal(); }
    public void continueCleanup(LocalPlayerContext context) {
        if (!cleanupPending() || !context.permitsNativeActions()) return;
        try { interruptedClose = context.menus().poll(context, interruptedClose); }
        catch (RuntimeException changedOwner) {
            interruptedClose = null;
            fail("owned inventory closure could no longer be reconciled: " + changedOwner.getMessage());
        }
    }
    // 准备被打断时先收完选栏记录和自己打开的库存，随后返回失败，让上层改用已经在手的后备物。
    private boolean closeInterrupted(LocalPlayerContext context) {
        if (selection != null) {
            selection = context.actions().poll(context, selection);
            if (!selection.terminal()) context.actions().retireOneShotForTaskBoundary(
                    context, selection, "landing item preparation left stable ground");
            selection = null;
        }
        if (inventoryTouched) {
            if (interruptedClose == null) {
                beginClosure(context);
                return false;
            }
            interruptedClose = context.menus().poll(context, interruptedClose);
            if (!interruptedClose.terminal()) return false;
            if (interruptedClose.status() != MenuReceipt.Status.CONFIRMED_APPLIED)
                return fail("interrupted landing inventory closure is uncertain: " + interruptedClose.detail());
        }
        return fail("left stable ground during item preparation; owned inventory closed, use only an already held fallback");
    }
    private void beginClosure(LocalPlayerContext context) {
        interruptedClose = context.menus().closeForTaskBoundary(context, 20,
                "landing inventory preparation ended before a safe departure");
        swap = null;
    }
    private boolean fail(String reason) { failed = true; detail = reason; return false; }
    public boolean failed() { return failed; }
    public boolean ready() { return ready; }
    public InteractionHand hand() { return hand; }
    public String diagnostic() { return detail; }
}
