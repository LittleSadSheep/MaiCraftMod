// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/** Default one-click-at-a-time menu port using MultiPlayerGameMode. */
public final class DefaultMenuPort implements MenuPort {
    private MenuReceipt active;

    @Override
    public MenuReceipt click(LocalPlayerContext context, int slot, int button, ClickType clickType,
                             MenuConfirmation confirmation, int timeoutTicks) {
        DefaultLocalPlayerContext current = requireSubmission(context);
        requireIdle();
        AbstractContainerMenu menu = current.player().containerMenu;
        if (slot < 0 || slot >= menu.slots.size()) throw new IllegalArgumentException("menu slot is out of range");
        current.claimMutation();
        MenuReceipt receipt = create(MenuReceipt.Kind.CLICK, current, menu, timeoutTicks, false, confirmation);
        try {
            current.gameMode().handleInventoryMouseClick(menu.containerId, slot, button, clickType, current.player());
        } catch (RuntimeException failure) {
            receipt.finish(MenuReceipt.Status.UNCERTAIN,
                    "menu click threw after entering the client transaction path");
        }
        return receipt;
    }

    @Override
    public MenuReceipt swapInventoryToHotbar(LocalPlayerContext context, int sourceInventorySlot,
                                             int hotbarSlot, int timeoutTicks) {
        DefaultLocalPlayerContext current = requireSubmission(context);
        if (sourceInventorySlot < 9 || sourceInventorySlot > 35) {
            throw new IllegalArgumentException("sourceInventorySlot must be a non-hotbar main inventory slot");
        }
        if (hotbarSlot < 0 || hotbarSlot > 8) throw new IllegalArgumentException("hotbarSlot is out of range");
        if (current.player().containerMenu != current.player().inventoryMenu) {
            throw new IllegalStateException("the player inventory menu must be active for hotbar staging");
        }
        requireIdle();
        ItemStack sourceBefore = current.player().getInventory().getItem(sourceInventorySlot).copy();
        ItemStack hotbarBefore = current.player().getInventory().getItem(hotbarSlot).copy();
        MenuConfirmation confirmation = MenuConfirmation.inventorySwap(
                sourceInventorySlot, hotbarSlot, sourceBefore, hotbarBefore);
        AbstractContainerMenu menu = current.player().inventoryMenu;
        current.claimMutation();
        MenuReceipt receipt = create(
                MenuReceipt.Kind.SWAP_TO_HOTBAR, current, menu, timeoutTicks, false, confirmation);
        try {
            // InventoryMenu uses the same indices for main inventory slots 9..35. The button is
            // the destination hotbar index for ClickType.SWAP.
            current.gameMode().handleInventoryMouseClick(
                    menu.containerId, sourceInventorySlot, hotbarSlot, ClickType.SWAP, current.player());
        } catch (RuntimeException failure) {
            receipt.finish(MenuReceipt.Status.UNCERTAIN,
                    "hotbar staging threw after entering the client transaction path");
        }
        return receipt;
    }

    @Override
    public MenuReceipt placeRecipe(LocalPlayerContext context, RecipeHolder<?> recipe, boolean shift,
                                   MenuConfirmation confirmation, int timeoutTicks) {
        DefaultLocalPlayerContext current = requireSubmission(context);
        requireIdle();
        AbstractContainerMenu menu = current.player().containerMenu;
        current.claimMutation();
        MenuReceipt receipt = create(
                MenuReceipt.Kind.PLACE_RECIPE, current, menu, timeoutTicks, false, confirmation);
        try {
            current.gameMode().handlePlaceRecipe(menu.containerId, recipe, shift);
        } catch (RuntimeException failure) {
            receipt.finish(MenuReceipt.Status.UNCERTAIN,
                    "recipe placement threw after entering the client transaction path");
        }
        return receipt;
    }

    @Override
    public MenuReceipt close(LocalPlayerContext context, int timeoutTicks) {
        DefaultLocalPlayerContext current = requireSubmission(context);
        requireIdle();
        AbstractContainerMenu menu = current.player().containerMenu;
        if (menu == current.player().inventoryMenu) {
            MenuReceipt receipt = create(MenuReceipt.Kind.CLOSE, current, menu, timeoutTicks, true,
                    MenuConfirmation.closedToInventory());
            receipt.finish(MenuReceipt.Status.CONFIRMED_APPLIED, "the inventory menu was already active");
            return receipt;
        }
        current.claimMutation();
        MenuReceipt receipt = create(MenuReceipt.Kind.CLOSE, current, menu, timeoutTicks, true,
                MenuConfirmation.closedToInventory());
        try {
            current.player().closeContainer();
        } catch (RuntimeException failure) {
            receipt.finish(MenuReceipt.Status.UNCERTAIN,
                    "menu close threw after entering the client transaction path");
        }
        return receipt;
    }

    @Override
    public MenuReceipt closeForTaskBoundary(
            LocalPlayerContext context, int timeoutTicks, String boundaryReason) {
        requireSubmission(context);
        if (active != null && !active.terminal()) {
            active.finish(MenuReceipt.Status.UNCERTAIN,
                    boundaryReason == null || boundaryReason.isBlank()
                            ? "the owning task ended before menu confirmation"
                            : boundaryReason);
        }
        // close() intentionally installs a new active receipt. The task may discard its reference,
        // but ClientActorBoundary.advance() still confirms that close on following ticks.
        return close(context, timeoutTicks);
    }

    @Override
    public MenuReceipt poll(LocalPlayerContext context, MenuReceipt receipt) {
        context.requireCurrent();
        requireActive(receipt);
        if (receipt.terminal()) return receipt;
        if (receipt.bodyEpoch() != context.bodyEpoch() ||
                receipt.controlRevision() != context.controlRevision() ||
                !context.permitsNativeActions()) {
            receipt.finish(MenuReceipt.Status.UNCERTAIN,
                    "body or control authority changed before menu confirmation");
            return receipt;
        }
        AbstractContainerMenu menu = context.player().containerMenu;
        boolean containerChanged = menu.containerId != receipt.containerId();
        boolean stateChanged = !containerChanged && menu.getStateId() != receipt.beforeStateId();
        if (containerChanged && !receipt.allowContainerChange()) {
            receipt.finish(MenuReceipt.Status.UNCERTAIN,
                    "the active container changed before the submitted click was confirmed");
            return receipt;
        }
        MenuConfirmation.Verdict verdict;
        try {
            // Exact postconditions (notably inventory-to-hotbar swaps) can become observable even
            // when this client menu's stateId does not advance. Always permit positive evidence to
            // settle the receipt. Negative/divergent evidence remains gated by an observed menu
            // synchronization below, so an unchanged pre-state during packet latency is not failure.
            verdict = receipt.confirmation().observe(context, receipt);
        } catch (RuntimeException observationFailure) {
            verdict = MenuConfirmation.Verdict.PENDING;
        }
        boolean synchronizationObserved = stateChanged
                || (containerChanged && receipt.allowContainerChange());
        switch (verdict) {
            case APPLIED -> {
                if (synchronizationObserved) {
                    receipt.finish(MenuReceipt.Status.CONFIRMED_APPLIED,
                            "the exact synchronized menu postcondition confirmed the transaction");
                } else if (receipt.appliedStableWithoutRevision(
                        context.tickRevision(), unacknowledgedStabilityTicks(context))) {
                    // MultiPlayerGameMode applies menu clicks optimistically before the server can
                    // correct them. When an accepted click produces no stateId update, absence of
                    // a correction beyond one observed RTT plus a small tick margin is the only
                    // available acknowledgement. A transient optimistic frame cannot pass this.
                    receipt.finish(MenuReceipt.Status.CONFIRMED_APPLIED,
                            "the exact menu postcondition remained stable beyond the server round-trip window");
                }
            }
            case NOT_APPLIED -> {
                receipt.clearUnacknowledgedApplied();
                if (synchronizationObserved) {
                    receipt.finish(MenuReceipt.Status.CONFIRMED_NOT_APPLIED,
                            "server-synchronized menu facts confirmed that the transaction was rejected");
                }
            }
            case DIVERGED -> {
                receipt.clearUnacknowledgedApplied();
                if (synchronizationObserved) {
                    receipt.finish(MenuReceipt.Status.DIVERGED,
                            "menu slots diverged from both the exact before and expected after state");
                }
            }
            case PENDING -> receipt.clearUnacknowledgedApplied();
        }
        if (!receipt.terminal() && context.tickRevision() >= receipt.deadlineTick()) {
            receipt.finish(MenuReceipt.Status.UNCERTAIN,
                    "the bounded menu confirmation window expired");
        }
        return receipt;
    }

    /**
     * Stable positive evidence substitutes for an omitted server slot echo only after enough time
     * for a rejection/correction to make a round trip. The two-tick margin covers server tick and
     * client packet scheduling; bounds keep stale/unknown latency data within the receipt window.
     */
    private static int unacknowledgedStabilityTicks(LocalPlayerContext context) {
        var info = context.connection().getPlayerInfo(context.player().getUUID());
        long latencyMillis = info == null ? 0L : Math.max(0, info.getLatency());
        int roundTripTicks = (int) Math.min(8L, (latencyMillis + 49L) / 50L);
        return Math.max(3, Math.min(10, roundTripTicks + 2));
    }

    void revokeForBoundary(String reason) {
        if (active != null && !active.terminal()) active.finish(MenuReceipt.Status.UNCERTAIN, reason);
    }

    /**
     * Human handoff is a transaction boundary. Closing through the vanilla player path makes the
     * server return any carried cursor stack to inventory (or apply its normal overflow behavior)
     * before automation releases the menu.
     */
    void revokeForHumanHandoff(net.minecraft.client.player.LocalPlayer player, String reason) {
        if (active != null && !active.terminal()) {
            active.finish(MenuReceipt.Status.UNCERTAIN, reason);
        }
        if (player == null) return;
        boolean automationMenuOpen = player.containerMenu != player.inventoryMenu;
        boolean carrying = !player.containerMenu.getCarried().isEmpty();
        if (!automationMenuOpen && !carrying) return;
        try {
            player.closeContainer();
        } catch (RuntimeException ignored) {
            // The receipt already says uncertain; the live menu remains visible to the human.
        }
    }

    private MenuReceipt create(MenuReceipt.Kind kind, LocalPlayerContext context,
                               AbstractContainerMenu menu, int timeoutTicks,
                               boolean allowContainerChange, MenuConfirmation confirmation) {
        MenuReceipt receipt = new MenuReceipt(
                kind, context, menu.containerId, menu.getStateId(), timeoutTicks,
                allowContainerChange, confirmation);
        active = receipt;
        return receipt;
    }

    private static DefaultLocalPlayerContext requireSubmission(LocalPlayerContext context) {
        if (!(context instanceof DefaultLocalPlayerContext current)) {
            throw new IllegalArgumentException("unsupported LocalPlayerContext implementation");
        }
        current.requireSubmissionAuthority();
        return current;
    }

    private void requireIdle() {
        if (active != null && !active.terminal()) {
            throw new IllegalStateException("a menu transaction is already awaiting confirmation");
        }
    }

    private void requireActive(MenuReceipt receipt) {
        if (receipt == null || receipt != active) {
            throw new IllegalArgumentException("the receipt is not the active menu transaction");
        }
    }
    void advance(LocalPlayerContext context) {
        MenuReceipt receipt = active;
        if (receipt != null && !receipt.terminal()) {
            poll(context, receipt);
        }
    }
}
