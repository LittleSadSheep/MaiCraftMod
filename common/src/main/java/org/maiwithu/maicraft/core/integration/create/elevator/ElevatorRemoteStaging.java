package org.maiwithu.maicraft.core.integration.create.elevator;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.DefaultBodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;

/** Reversible preparation of one real handheld controller, including cancellation mid-swap. */
final class ElevatorRemoteStaging {
    private VisibleMenuSession menu = new VisibleMenuSession();
    private MenuReceipt exchange;
    private Screen inventoryScreen;
    private int source = -1, hotbar, originalSelected;
    private ItemStack sourceBefore, hotbarBefore;
    private boolean swapped, restoring, restoreFinished, opened, menuClosed, closing;
    boolean changed, uncertain;
    String failure;

    boolean ready(LocalPlayerContext ctx, int slot, ElevatorActions actions) {
        if (source == -1) {
            source = slot; originalSelected = ctx.player().getInventory().selected;
            hotbar = slot < 9 ? slot : originalSelected;
            sourceBefore = ctx.player().getInventory().getItem(source).copy();
            hotbarBefore = ctx.player().getInventory().getItem(hotbar).copy();
        }
        if (!settle(ctx) || failure != null) return false;
        if (source >= 9 && !swapped) {
            if (!inventoryReady(ctx) || !ctx.mutationAvailable()) return false;
            if (!same(ctx.player().getInventory().getItem(source), sourceBefore)
                    || !same(ctx.player().getInventory().getItem(hotbar), hotbarBefore)) {
                failure = "inventory changed before controller preparation"; return false;
            }
            exchange = ctx.menus().swapInventoryToHotbar(ctx, source, hotbar, 30);
            changed = true; return false;
        }
        if (!close(ctx)) return false;
        if (ctx.player().getInventory().selected != hotbar) {
            if (actions.selectHotbar(ctx, hotbar)) changed = true;
            return false;
        }
        return !actions.pending();
    }

    boolean cleanup(LocalPlayerContext ctx, ElevatorActions actions) {
        if (source < 0) return true;
        if (!settle(ctx)) return false;
        if (source >= 9 && !restoreFinished) {
            ItemStack atSource = ctx.player().getInventory().getItem(source), atHotbar = ctx.player().getInventory().getItem(hotbar);
            if (same(atSource, sourceBefore) && same(atHotbar, hotbarBefore)) restoreFinished = true;
            else if (same(atSource, hotbarBefore) && same(atHotbar, sourceBefore)) {
                if (!inventoryReady(ctx) || !ctx.mutationAvailable()) return false;
                restoring = true;
                exchange = ctx.menus().swapInventoryToHotbar(ctx, source, hotbar, 30);
                return false;
            } else {
                uncertain = true; restoreFinished = true;
                failure = "controller inventory was changed externally; conflicting slots were not overwritten";
            }
        }
        if (!close(ctx)) return false;
        inventoryScreen = null;
        if (ctx.player().getInventory().selected != originalSelected) {
            actions.selectHotbar(ctx, originalSelected); return false;
        }
        if (actions.pending()) return false;
        source = -2; return true;
    }

    boolean ownsScreen(LocalPlayerContext ctx) {
        return inventoryScreen != null && ctx.minecraft().screen == inventoryScreen
                && inventoryScreen instanceof InventoryScreen && ctx.player().containerMenu == ctx.player().inventoryMenu;
    }
    boolean active() { return source >= 0 && (changed || opened); }
    boolean pending() { return exchange != null || closing; }

    private boolean inventoryReady(LocalPlayerContext ctx) {
        if (ctx.player().containerMenu != ctx.player().inventoryMenu
                || !DefaultBodyControlPort.permitsWorldMovement(ctx.minecraft().screen) && !ownsScreen(ctx)) return false;
        opened = true;
        if (menuClosed) { menu = new VisibleMenuSession(); menuClosed = false; }
        boolean ready = menu.inventoryReady(ctx);
        if (ctx.minecraft().screen instanceof InventoryScreen) inventoryScreen = ctx.minecraft().screen;
        return ready;
    }

    private boolean close(LocalPlayerContext ctx) {
        if (!opened || menuClosed) return true;
        closing = true;
        if (!menu.close(ctx)) return false;
        closing = false; menuClosed = true; inventoryScreen = null;
        return true;
    }

    private boolean settle(LocalPlayerContext ctx) {
        if (exchange == null) return true;
        exchange = ctx.menus().poll(ctx, exchange);
        if (!exchange.terminal()) return false;
        if (exchange.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
            uncertain = true; failure = "controller inventory exchange was not confirmed: " + exchange.detail();
        }
        if (restoring) restoreFinished = true;
        else swapped = exchange.status() == MenuReceipt.Status.CONFIRMED_APPLIED;
        exchange = null;
        return true;
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return a.getCount() == b.getCount() && ItemStack.isSameItemSameComponents(a, b);
    }
}
