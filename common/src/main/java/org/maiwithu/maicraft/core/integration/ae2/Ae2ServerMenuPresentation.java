// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import java.util.function.BooleanSupplier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import java.util.Objects;

/** 真实终端菜单就是交互上下文，并会一直保留到原生回执和库存同步完成。 */
final class Ae2ServerMenuPresentation {
    private final AbstractContainerMenu menu;
    private final BooleanSupplier sameTerminal;
    private long changedTick = Long.MIN_VALUE;

    Ae2ServerMenuPresentation(AbstractContainerMenu menu, BooleanSupplier sameTerminal) {
        this.menu = Objects.requireNonNull(menu);
        this.sameTerminal = Objects.requireNonNull(sameTerminal);
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
