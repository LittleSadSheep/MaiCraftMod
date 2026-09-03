// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** A displayed menu, including a rendered frame, is required before automated transactions. */
public final class MenuVisibility {
    private static Screen renderedScreen;
    private static long renderedFrame;
    private Screen observedScreen;
    private AbstractContainerMenu observedMenu;
    private long readyTick;
    private long afterFrame;

    /** Keeps the actual player inventory and its slot layout visible even in creative mode. */
    public static final class PlayerInventoryScreen extends InventoryScreen {
        public PlayerInventoryScreen(LocalPlayer player) { super(player); }
    }

    public static boolean matches(Minecraft minecraft, AbstractContainerMenu menu) {
        return minecraft.screen instanceof AbstractContainerScreen<?> screen && screen.getMenu() == menu;
    }

    public static boolean inventoryVisible(Minecraft minecraft, LocalPlayer player) {
        return matches(minecraft, player.inventoryMenu);
    }

    /** Called after rendering the screen, never from a game tick. */
    public static void rendered(Minecraft minecraft) {
        renderedScreen = minecraft.screen;
        renderedFrame++;
    }

    boolean ready(LocalPlayerContext context) {
        observe(context);
        return observedScreen != null && matches(context.minecraft(), context.player().containerMenu)
                && context.tickRevision() >= readyTick
                && renderedScreen == observedScreen && renderedFrame > afterFrame;
    }

    void observe(LocalPlayerContext context) {
        Screen screen = context.minecraft().screen;
        AbstractContainerMenu menu = context.player().containerMenu;
        if (screen != observedScreen || menu != observedMenu) {
            observedScreen = screen;
            observedMenu = menu;
            readyTick = context.tickRevision() + 4;
            afterFrame = renderedFrame;
        }
    }

    void changed(LocalPlayerContext context) {
        observe(context);
        readyTick = Math.max(readyTick, context.tickRevision() + 2);
        afterFrame = renderedFrame;
    }

    void reset() {
        observedScreen = null;
        observedMenu = null;
        renderedScreen = null;
    }
}
