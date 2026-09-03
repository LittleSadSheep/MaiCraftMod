// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import sun.misc.Unsafe;

/** Exercises the production visibility state machine without creating a graphics window. */
public final class MenuVisibilityTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Unsafe memory = (Unsafe) singleton.get(null);
        Minecraft minecraft = (Minecraft) memory.allocateInstance(Minecraft.class);
        LocalPlayer player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
        InventoryMenu inventory = (InventoryMenu) memory.allocateInstance(InventoryMenu.class);
        assign(Player.class, player, "inventoryMenu", inventory);
        player.containerMenu = inventory;
        long[] tick = {0};
        LocalPlayerContext context = (LocalPlayerContext) Proxy.newProxyInstance(
                LocalPlayerContext.class.getClassLoader(), new Class<?>[]{LocalPlayerContext.class},
                (proxy, method, values) -> switch (method.getName()) {
                    case "minecraft" -> minecraft;
                    case "player" -> player;
                    case "tickRevision" -> tick[0];
                    default -> throw new AssertionError("Unexpected context access: " + method);
                });
        MenuVisibility visibility = new MenuVisibility();
        visibility.reset();
        check(!visibility.ready(context), "the permanent inventory menu is not a visible GUI");
        MenuVisibility.rendered(minecraft);
        tick[0] = 10;
        check(!visibility.ready(context), "world frames cannot make an unopened inventory ready");

        var screen = (MenuVisibility.PlayerInventoryScreen)
                memory.allocateInstance(MenuVisibility.PlayerInventoryScreen.class);
        assign(AbstractContainerScreen.class, screen, "menu", inventory);
        minecraft.screen = screen;
        check(MenuVisibility.inventoryVisible(minecraft, player), "inventory screen identity matches");
        check(!visibility.ready(context), "opening the GUI does not permit a same-tick mutation");
        MenuVisibility.rendered(minecraft);
        tick[0] = 13;
        check(!visibility.ready(context), "a rendered opening still needs its initial dwell");
        tick[0] = 14;
        check(visibility.ready(context), "a rendered GUI becomes ready after the opening dwell");
        check(MenuConfirmation.closedToInventory().observe(context, null)
                == MenuConfirmation.Verdict.PENDING, "an inventory GUI is not a confirmed close");

        visibility.changed(context);
        tick[0] = 15;
        MenuVisibility.rendered(minecraft);
        check(!visibility.ready(context), "a rendered transaction still needs its dwell");
        tick[0] = 16;
        check(visibility.ready(context), "a rendered transaction settles after its dwell");
        visibility.changed(context);
        tick[0] = 30;
        check(!visibility.ready(context), "ticks alone never replace a post-transaction frame");
        MenuVisibility.rendered(minecraft);
        check(visibility.ready(context), "a new frame unlocks the settled transaction");

        var replacement = (MenuVisibility.PlayerInventoryScreen)
                memory.allocateInstance(MenuVisibility.PlayerInventoryScreen.class);
        assign(AbstractContainerScreen.class, replacement, "menu", inventory);
        minecraft.screen = replacement;
        check(!visibility.ready(context), "replacing a screen restarts its visibility lease");
        tick[0] = 40;
        check(!visibility.ready(context), "a previous screen's frame cannot authorize its replacement");
        MenuVisibility.rendered(minecraft);
        check(visibility.ready(context), "the replacement's own rendered frame is accepted");
        player.containerMenu = (InventoryMenu) memory.allocateInstance(InventoryMenu.class);
        check(!visibility.ready(context), "the screen must display the exact active menu object");
        tick[0] = 50;
        MenuVisibility.rendered(minecraft);
        check(!visibility.ready(context), "waiting cannot authorize a mismatched menu");
        minecraft.screen = null;
        check(!visibility.ready(context), "closing the GUI immediately revokes visibility");
        check(MenuConfirmation.closedToInventory().observe(context, null)
                == MenuConfirmation.Verdict.PENDING, "a hidden external menu is not a confirmed close");
        player.containerMenu = inventory;
        check(MenuConfirmation.closedToInventory().observe(context, null)
                == MenuConfirmation.Verdict.APPLIED, "close needs both inventory and no screen");
        visibility.reset();
        System.out.println("MenuVisibilityTest: passed");
    }

    private static void assign(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
