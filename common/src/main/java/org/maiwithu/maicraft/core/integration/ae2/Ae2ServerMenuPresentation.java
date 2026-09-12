// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import java.util.function.BooleanSupplier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuVisibility;

/** The actual terminal menu is the interaction context, held through native receipts and inventory synchronization. */
final class Ae2ServerMenuPresentation {
    private final AbstractContainerMenu menu;
    private final BooleanSupplier sameTerminal;
    private long changedTick = Long.MIN_VALUE;

    Ae2ServerMenuPresentation(AbstractContainerMenu menu, BooleanSupplier sameTerminal) {
        this.menu = java.util.Objects.requireNonNull(menu);
        this.sameTerminal = java.util.Objects.requireNonNull(sameTerminal);
    }

    boolean owns(LocalPlayerContext context) {
        return context.player().containerMenu == menu && menu != context.player().inventoryMenu
                && MenuVisibility.matches(context.minecraft(), menu);
    }

    boolean ready(LocalPlayerContext context) {
        requireCurrent(context);
        return context.menus().ensureVisible(context);
    }

    void requireCurrent(LocalPlayerContext context) {
        requireOwned(context);
        if (!sameTerminal.getAsBoolean())
            throw new Ae2ProtocolException("server_terminal_menu_changed: the exact observed terminal GUI must remain open");
    }

    private void requireOwned(LocalPlayerContext context) {
        if (!owns(context) || !menu.getCarried().isEmpty())
            throw new Ae2ProtocolException("server_terminal_menu_changed: preserve the changed GUI or cursor");
    }

    int containerId() { return menu.containerId; }
    void changed(LocalPlayerContext context) { changedTick = context.tickRevision(); context.menus().interactionSubmitted(context); }
    boolean readyToClose(LocalPlayerContext context) {
        requireOwned(context);
        return changedTick != Long.MIN_VALUE && context.tickRevision() - changedTick >= 4
                && context.menus().ensureVisible(context);
    }
}
