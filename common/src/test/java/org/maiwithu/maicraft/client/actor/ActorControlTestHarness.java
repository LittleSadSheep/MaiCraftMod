// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.InventoryMenu;
import sun.misc.Unsafe;

/** A real actor context with inert player input; never constructs a window or connects to a world. */
final class ActorControlTestHarness {
    final Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
    final Minecraft minecraft = allocate(Minecraft.class);
    final LocalPlayer player = allocate(TestPlayer.class);
    final ClientActorBoundary actor = new ClientActorBoundary(minecraft);
    final DefaultBodyControlPort body = actor.body();
    final DefaultNativeActionPort actions = actor.actions();
    DefaultLocalPlayerContext context;
    long tick;

    ActorControlTestHarness() throws Exception {
        minecraft.player = player;
        field(Minecraft.class, "gameThread").set(minecraft, Thread.currentThread());
        field(ClientActorBoundary.class, "observedPlayer").set(actor, player);
        InventoryMenu inventory = allocate(InventoryMenu.class);
        field(LocalPlayer.class, "inventoryMenu").set(player, inventory);
        player.containerMenu = inventory;
        player.input = new Input();
        body.requestAutomation(player);
        body.fulfillAutomationRequest(player);
        nextTick(true);
    }

    DefaultLocalPlayerContext nextTick(boolean permitsNativeActions) throws Exception {
        tick++;
        field(ClientActorBoundary.class, "tickRevision").setLong(actor, tick);
        context = new DefaultLocalPlayerContext(actor, minecraft, player, null, null, null,
                0, 0, tick, permitsNativeActions);
        field(ClientActorBoundary.class, "activeContext").set(actor, context);
        body.beginTick(tick);
        return context;
    }

    Screen inventoryScreen() throws Exception {
        var screen = allocate(MenuVisibility.PlayerInventoryScreen.class);
        field(AbstractContainerScreen.class, "menu").set(screen, player.inventoryMenu);
        return screen;
    }

    Object visibility() throws Exception {
        return field(DefaultMenuPort.class, "visibility").get(actor.menus());
    }

    <T> T allocate(Class<T> type) throws InstantiationException {
        return type.cast(memory.allocateInstance(type));
    }

    static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** Unsafe skips the constructor; sprinting is recorded without entity or network mutation. */
    private static final class TestPlayer extends LocalPlayer {
        boolean sprinting;
        boolean sleeping;
        private TestPlayer() { super(null, null, null, null, null, false, false); }
        @Override public void setSprinting(boolean value) { sprinting = value; }
        @Override public boolean isSprinting() { return sprinting; }
        @Override public boolean isSleeping() { return sleeping; }
    }
}
