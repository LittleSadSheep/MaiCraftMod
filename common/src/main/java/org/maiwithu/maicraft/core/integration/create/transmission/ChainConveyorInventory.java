// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;

/** Exact owned inventory staging; Create consumes chains by item type, so special components are refused. */
final class ChainConveyorInventory {
    private final FirstPersonActionGate selection = new FirstPersonActionGate();
    private final VisibleMenuSession restoration = new VisibleMenuSession();
    private FirstPersonActionGate.ConfirmedSwap swap;
    private MenuReceipt swapBack;
    private NativeActionReceipt selectBack;
    private final int originalSelected;
    private int source = -1;
    private boolean swappedBack;
    ChainConveyorInventory(LocalPlayer player) { originalSelected = player.getInventory().selected; }
    static boolean plain(ItemStack stack) { return !stack.isEmpty() && stack.is(Items.CHAIN) && stack.getComponentsPatch().isEmpty(); }
    static int mainCount(LocalPlayer player) {
        return player.getInventory().items.stream().filter(stack -> stack.is(Items.CHAIN)).mapToInt(ItemStack::getCount).sum();
    }
    static int count(LocalPlayer player) {
        return mainCount(player) + (player.getOffhandItem().is(Items.CHAIN) ? player.getOffhandItem().getCount() : 0);
    }
    static void requirePlainChains(LocalPlayer player) {
        for (ItemStack stack : player.getInventory().items)
            if (stack.is(Items.CHAIN) && !plain(stack)) throw new IllegalArgumentException("chain_conveyor_special_chain_components_present");
        if (player.getOffhandItem().is(Items.CHAIN) && !plain(player.getOffhandItem()))
            throw new IllegalArgumentException("chain_conveyor_special_chain_components_present");
    }
    boolean select(LocalPlayerContext context) {
        if (source < 0) {
            for (int slot = 0; slot < context.player().getInventory().items.size(); slot++)
                if (plain(context.player().getInventory().getItem(slot))) { source = slot; break; }
            if (source < 0) throw new IllegalArgumentException("chain_conveyor_main_hand_chain_missing");
        }
        var state = selection.select(context.player(), source);
        var observedSwap = selection.takeConfirmedSwap(); if (observedSwap != null) swap = observedSwap;
        if (state == FirstPersonActionGate.Status.FAILED) throw new IllegalStateException("chain_conveyor_inventory_selection_failed: " + selection.failure());
        if (state != FirstPersonActionGate.Status.READY) return false;
        if (!plain(context.player().getMainHandItem())) throw new IllegalArgumentException("chain_conveyor_selected_stack_changed");
        return true;
    }
    boolean restore(LocalPlayerContext context) {
        if (swapBack != null) {
            swapBack = context.menus().poll(context, swapBack);
            if (!swapBack.terminal()) return false;
            if (swapBack.status() != MenuReceipt.Status.CONFIRMED_APPLIED) throw new IllegalStateException("chain_conveyor_inventory_restore_unconfirmed");
            swapBack = null; swappedBack = true;
        }
        if (swap != null && !swappedBack) {
            ItemStack displaced = context.player().getInventory().getItem(swap.source());
            ItemStack remainder = context.player().getInventory().getItem(swap.hotbar());
            if (!same(displaced, swap.hotbarBefore()) || !remainder.isEmpty() && (!plain(remainder) || remainder.getCount() > swap.sourceBefore().getCount()))
                throw new IllegalArgumentException("chain_conveyor_inventory_restore_target_changed");
            if (!restoration.inventoryReady(context) || !context.mutationAvailable()) return false;
            swapBack = context.menus().swapInventoryToHotbar(context, swap.source(), swap.hotbar(), 20);
            return false;
        }
        if (!restoration.close(context)) return false;
        if (selectBack != null) {
            selectBack = context.actions().poll(context, selectBack);
            if (!selectBack.terminal()) return false;
            if (selectBack.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) throw new IllegalStateException("chain_conveyor_selected_slot_restore_unconfirmed");
            selectBack = null;
        }
        if (context.player().getInventory().selected != originalSelected) {
            if (!context.mutationAvailable()) return false;
            selectBack = context.actions().selectHotbar(context, originalSelected, 20); return false;
        }
        return true;
    }
    void close(LocalPlayer player) { selection.reset(); restoration.cleanup(player); }
    private static boolean same(ItemStack a, ItemStack b) { return a.getCount() == b.getCount() && ItemStack.isSameItemSameComponents(a, b); }
}
