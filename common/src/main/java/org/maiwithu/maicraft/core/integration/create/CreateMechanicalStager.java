// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;

/** Receipt-driven inventory-menu staging. It never writes an inventory or selected slot directly. */
final class CreateMechanicalStager {
    enum Status { RUNNING, READY, RESTORED, FAILED, UNCERTAIN }

    private static final int CONFIRM_TICKS = 20;

    private final int originalSelected;
    private MenuReceipt menuReceipt;
    private NativeActionReceipt selectReceipt;
    private int activeSwapSource = -1;
    private int activeSwapHotbar = -1;
    private boolean swapRestorationPending;
    private boolean closeAfterSwap;
    private boolean inventoryScreenOwned;
    private int pendingSelect = -1;
    private ItemStack expectedDisplacedAtSource = ItemStack.EMPTY;
    private String detail = "inventory staging is pending";

    CreateMechanicalStager(LocalPlayer player) {
        this.originalSelected = player.getInventory().selected;
    }

    CreateMechanicalStager(Snapshot snapshot) {
        this.originalSelected = snapshot.originalSelected();
        this.activeSwapSource = snapshot.activeSwapSource();
        this.activeSwapHotbar = snapshot.activeSwapHotbar();
        this.expectedDisplacedAtSource = snapshot.displacedAtSource().copy();
    }

    record Snapshot(
            int originalSelected,
            int activeSwapSource,
            int activeSwapHotbar,
            ItemStack displacedAtSource) {
        Snapshot {
            displacedAtSource = displacedAtSource.copy();
        }
    }

    Status ensureChainSelected(LocalPlayerContext context, Item chainItem) {
        Status pending = pollPending(context);
        if (pending == Status.FAILED || pending == Status.UNCERTAIN || pending == Status.RUNNING) {
            return pending;
        }
        LocalPlayer player = context.player();
        if (player.containerMenu != player.inventoryMenu) {
            menuReceipt = context.menus().close(context, CONFIRM_TICKS);
            detail = "closing the active menu before hotbar staging";
            return Status.RUNNING;
        }
        ItemStack selected = player.getMainHandItem();
        if (usable(selected, chainItem) > 0) return Status.READY;

        // An exhausted staged stack has to be swapped back before another source is chosen.
        if (activeSwapSource >= 0) {
            if (!readyInventory(context)) return Status.RUNNING;
            menuReceipt = context.menus().swapInventoryToHotbar(
                    context, activeSwapSource, activeSwapHotbar, CONFIRM_TICKS);
            closeAfterSwap = true;
            swapRestorationPending = true;
            detail = "returning the displaced hotbar stack before staging the next material stack";
            return Status.RUNNING;
        }

        int source = findUsableSlot(player, chainItem);
        if (source < 0) {
            detail = "no default-component encased chain drive remains in inventory";
            return Status.FAILED;
        }
        if (source < 9) {
            if (player.getInventory().selected == source) return Status.READY;
            pendingSelect = source;
            selectReceipt = context.actions().selectHotbar(context, source, CONFIRM_TICKS);
            detail = "selecting a chain-drive hotbar stack";
            return Status.RUNNING;
        }

        int hotbar = chooseSafeHotbar(player);
        if (hotbar < 0) {
            detail = "no safe hotbar slot can temporarily hold the chain-drive stack";
            return Status.FAILED;
        }
        if (!readyInventory(context)) return Status.RUNNING;
        activeSwapSource = source;
        activeSwapHotbar = hotbar;
        expectedDisplacedAtSource = player.getInventory().getItem(hotbar).copy();
        menuReceipt = context.menus().swapInventoryToHotbar(context, source, hotbar, CONFIRM_TICKS);
        closeAfterSwap = true;
        pendingSelect = hotbar;
        detail = "staging a chain-drive stack through a confirmed inventory swap";
        return Status.RUNNING;
    }

    Status restore(LocalPlayerContext context) {
        Status pending = pollPending(context);
        if (pending == Status.FAILED || pending == Status.UNCERTAIN || pending == Status.RUNNING) {
            return pending;
        }
        LocalPlayer player = context.player();
        if (player.containerMenu != player.inventoryMenu) {
            menuReceipt = context.menus().close(context, CONFIRM_TICKS);
            detail = "closing the active menu before inventory restoration";
            return Status.RUNNING;
        }
        if (activeSwapSource >= 0) {
            if (!readyInventory(context)) return Status.RUNNING;
            menuReceipt = context.menus().swapInventoryToHotbar(
                    context, activeSwapSource, activeSwapHotbar, CONFIRM_TICKS);
            closeAfterSwap = true;
            swapRestorationPending = true;
            detail = "restoring the displaced hotbar stack";
            return Status.RUNNING;
        }
        if (player.getInventory().selected != originalSelected) {
            pendingSelect = originalSelected;
            selectReceipt = context.actions().selectHotbar(context, originalSelected, CONFIRM_TICKS);
            detail = "restoring the original selected hotbar slot";
            return Status.RUNNING;
        }
        detail = "inventory and selected hotbar slot restored";
        return Status.RESTORED;
    }

    boolean hasPendingReceipt() {
        return closeAfterSwap || menuReceipt != null && !menuReceipt.terminal()
                || selectReceipt != null && !selectReceipt.terminal();
    }

    Snapshot snapshot() {
        return hasPendingReceipt() ? null : new Snapshot(originalSelected,
                activeSwapSource, activeSwapHotbar, expectedDisplacedAtSource);
    }

    boolean validateSnapshot(LocalPlayer player, Item chainItem) {
        if (activeSwapSource < 0) return true;
        if (activeSwapSource >= player.getInventory().getContainerSize()
                || activeSwapHotbar < 0 || activeSwapHotbar >= 9) return false;
        ItemStack displaced = player.getInventory().getItem(activeSwapSource);
        ItemStack hotbar = player.getInventory().getItem(activeSwapHotbar);
        return same(displaced, expectedDisplacedAtSource)
                && (hotbar.isEmpty() || usable(hotbar, chainItem) > 0);
    }

    String detail() {
        return detail;
    }

    private Status pollPending(LocalPlayerContext context) {
        if (menuReceipt == null && closeAfterSwap) {
            menuReceipt = context.menus().close(context, CONFIRM_TICKS);
            closeAfterSwap = false;
            detail = "closing the inventory GUI before returning to the world";
            return Status.RUNNING;
        }
        if (menuReceipt != null) {
            menuReceipt = context.menus().poll(context, menuReceipt);
            if (!menuReceipt.terminal()) return Status.RUNNING;
            MenuReceipt.Status status = menuReceipt.status();
            MenuReceipt.Kind kind = menuReceipt.kind();
            String receiptDetail = menuReceipt.detail();
            menuReceipt = null;
            if (status != MenuReceipt.Status.CONFIRMED_APPLIED) {
                closeAfterSwap = false;
                if (status == MenuReceipt.Status.CONFIRMED_NOT_APPLIED && kind == MenuReceipt.Kind.SWAP_TO_HOTBAR) {
                    if (!swapRestorationPending) {
                        activeSwapSource = -1;
                        activeSwapHotbar = -1;
                        expectedDisplacedAtSource = ItemStack.EMPTY;
                        pendingSelect = -1;
                    }
                    // A rejected restoration leaves the known staged swap in place; a future
                    // continuation may safely try that restoration again.
                    swapRestorationPending = false;
                }
                detail = "inventory transaction was not definitely applied: " + receiptDetail;
                return status == MenuReceipt.Status.UNCERTAIN
                        || status == MenuReceipt.Status.DIVERGED
                        ? Status.UNCERTAIN : Status.FAILED;
            }
            if (swapRestorationPending) {
                activeSwapSource = -1;
                activeSwapHotbar = -1;
                expectedDisplacedAtSource = ItemStack.EMPTY;
                swapRestorationPending = false;
            }
            if (closeAfterSwap) return Status.RUNNING;
            if (context.minecraft().screen == null) inventoryScreenOwned = false;
            // A staged swap is confirmed; select it in a later native mutation.
            if (pendingSelect >= 0 && context.player().getInventory().selected != pendingSelect) {
                int slot = pendingSelect;
                selectReceipt = context.actions().selectHotbar(context, slot, CONFIRM_TICKS);
                detail = "selecting the confirmed staged hotbar stack";
                return Status.RUNNING;
            }
            return Status.READY;
        }
        if (selectReceipt != null) {
            selectReceipt = context.actions().poll(context, selectReceipt);
            if (!selectReceipt.terminal()) return Status.RUNNING;
            NativeActionReceipt.Status status = selectReceipt.status();
            String receiptDetail = selectReceipt.detail();
            selectReceipt = null;
            pendingSelect = -1;
            if (status != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
                detail = "hotbar selection was not definitely applied: " + receiptDetail;
                return status == NativeActionReceipt.Status.UNCERTAIN
                        || status == NativeActionReceipt.Status.DIVERGED
                        ? Status.UNCERTAIN : Status.FAILED;
            }
            return Status.READY;
        }
        return Status.READY;
    }

    private boolean readyInventory(LocalPlayerContext context) {
        inventoryScreenOwned = true;
        return context.menus().ensureVisible(context);
    }

    void closeForTaskBoundary(LocalPlayerContext context) {
        if (!inventoryScreenOwned || !MenuVisibility.inventoryVisible(context.minecraft(), context.player())) return;
        context.menus().closeForTaskBoundary(context, CONFIRM_TICKS, "Create inventory staging ended");
        inventoryScreenOwned = false;
    }

    private static int findUsableSlot(LocalPlayer player, Item item) {
        int selected = player.getInventory().selected;
        if (usable(player.getInventory().getItem(selected), item) > 0) return selected;
        for (int slot = 0; slot < Math.min(36, player.getInventory().getContainerSize()); slot++) {
            if (usable(player.getInventory().getItem(slot), item) > 0) return slot;
        }
        return -1;
    }

    private static int chooseSafeHotbar(LocalPlayer player) {
        int selected = player.getInventory().selected;
        if (safeDisplaced(player.getInventory().getItem(selected))) return selected;
        for (int slot = 0; slot < 9; slot++) {
            if (safeDisplaced(player.getInventory().getItem(slot))) return slot;
        }
        return -1;
    }

    static int usable(ItemStack stack, Item item) {
        return !stack.isEmpty() && stack.getItem() == item
                && stack.getComponentsPatch().isEmpty() ? stack.getCount() : 0;
    }

    static boolean safeDisplaced(ItemStack stack) {
        return stack.isEmpty() || !stack.isDamageableItem()
                && stack.getCount() >= 1 && stack.getCount() <= stack.getMaxStackSize()
                && stack.getMaxStackSize() <= 64 && stack.getComponentsPatch().isEmpty();
    }

    private static boolean same(ItemStack left, ItemStack right) {
        return left.getCount() == right.getCount()
                && ItemStack.isSameItemSameComponents(left, right);
    }
}
