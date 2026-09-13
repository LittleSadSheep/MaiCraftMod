// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.core.integration.machine.MachineMenuHandParking;

/** Visible-GUI fixture with delayed native swap/close receipts; never emulates creative clearing or a world click. */
public final class MachineMenuHandParkingTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        fullHotbarParksItsExactStackAndWaitsForClose();
        fullInventoryAndForeignContentsStayUntouched();
        replacementScreenIsNeverClosed();
    }
    private static void fullHotbarParksItsExactStackAndWaitsForClose() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            Harness h = new Harness(world); List<ItemStack> before = new ArrayList<>(); world.inventory.items.forEach(stack -> before.add(stack.copy()));
            var parking = new MachineMenuHandParking();
            check(h.step(parking) == MachineMenuHandParking.Status.RUNNING && h.opens == 1 && h.swaps == 0, "open a real inventory surface before any swap");
            h.step(parking); check(h.swaps == 0, "an unrendered inventory cannot receive the staging click");
            h.rendered = true; h.step(parking); check(h.swaps == 1 && !world.player.getMainHandItem().isEmpty(), "swap submission is not a confirmed empty hand");
            h.step(parking); check(h.swaps == 1 && h.closes == 0, "pending slot receipt is not replayed or hidden by an early close");
            h.allowSwap = true; h.step(parking);
            check(world.player.getMainHandItem().isEmpty() && same(world.inventory.getItem(12), before.get(4)) && h.closes == 0,
                    "selected named/damaged tool is preserved in the actual empty main slot before closing");
            h.step(parking); check(h.closes == 1 && world.h.minecraft.screen != null, "close submission does not let world interaction proceed");
            h.step(parking); check(h.closes == 1, "pending close is polled rather than resubmitted");
            h.allowClose = true;
            check(h.step(parking) == MachineMenuHandParking.Status.READY && world.h.minecraft.screen == null, "empty hand becomes ready only after confirmed GUI closure");
            for (int slot = 0; slot < 36; slot++) check(same(world.inventory.getItem(slot), slot == 4 ? ItemStack.EMPTY : slot == 12 ? before.get(4) : before.get(slot)),
                    "inventory transaction changed an unrelated stack or its components");
            check(h.step(parking) == MachineMenuHandParking.Status.READY && h.opens == 1 && h.swaps == 1 && h.closes == 1,
                    "confirmed preparation is idempotent");
        }
    }
    private static void fullInventoryAndForeignContentsStayUntouched() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            Harness h = new Harness(world); world.inventory.setItem(12, new ItemStack(Items.DIRT, 64));
            check(h.step(new MachineMenuHandParking()) == MachineMenuHandParking.Status.FAILED && h.opens == 0,
                    "a fully occupied inventory is rejected without manufacturing an empty slot");
            world.inventory.setItem(12, ItemStack.EMPTY); world.player.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND));
            check(h.step(new MachineMenuHandParking()) == MachineMenuHandParking.Status.FAILED && world.player.inventoryMenu.getCarried().is(Items.DIAMOND),
                    "an unrelated cursor is never cleared");
            world.player.inventoryMenu.setCarried(ItemStack.EMPTY); world.player.inventoryMenu.getSlot(1).set(new ItemStack(Items.DIAMOND));
            check(h.step(new MachineMenuHandParking()) == MachineMenuHandParking.Status.FAILED && h.opens == 0, "unrelated crafting-grid contents are not returned or discarded during close");
            world.player.inventoryMenu.getSlot(1).set(ItemStack.EMPTY); Screen foreign = h.screen(); world.h.minecraft.screen = foreign;
            var parking = new MachineMenuHandParking(); check(h.step(parking) == MachineMenuHandParking.Status.FAILED, "preexisting inventory screen is not adopted");
            parking.cleanup(world.player); check(world.h.minecraft.screen == foreign && h.closes == 0 && h.swaps == 0, "foreign GUI and all inventory stacks remain untouched");
        }
    }
    private static void replacementScreenIsNeverClosed() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            Harness h = new Harness(world); var parking = new MachineMenuHandParking(); h.step(parking); h.rendered = true; h.step(parking);
            Screen foreign = h.screen(); world.h.minecraft.screen = foreign; h.allowSwap = true; h.step(parking);
            check(h.step(parking) == MachineMenuHandParking.Status.FAILED && parking.uncertain(), "replacement after a submitted swap stays explicit");
            parking.cleanup(world.player);
            check(world.h.minecraft.screen == foreign && h.closes == 0, "cleanup closes only the exact inventory surface this preparation opened");
        }
    }
    private static final class Harness {
        final InteractionWorldTestHarness world; final LocalPlayerContext context;
        int opens, swaps, closes, source, hotbar; long tick, mutationTick = -1;
        boolean visible, rendered, allowSwap, allowClose; MenuReceipt swap, close;
        Harness(InteractionWorldTestHarness world) throws Exception {
            this.world = world; world.nextTick(); world.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            NonNullList<Slot> grid = NonNullList.create(); var contents = new SimpleContainer(5);
            for (int slot = 0; slot < 5; slot++) grid.add(new Slot(contents, slot, 0, 0));
            ActorControlTestHarness.field(AbstractContainerMenu.class, "slots").set(world.player.inventoryMenu, grid);
            for (int slot = 0; slot < 36; slot++) world.inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 1 + slot % 9));
            world.inventory.setItem(12, ItemStack.EMPTY); world.inventory.selected = 4;
            var tool = new ItemStack(Items.IRON_PICKAXE); tool.set(DataComponents.CUSTOM_NAME, Component.literal("Owner's tool")); tool.setDamageValue(7); world.inventory.setItem(4, tool);
            MenuPort menus = (MenuPort) Proxy.newProxyInstance(MenuPort.class.getClassLoader(), new Class<?>[]{MenuPort.class}, (proxy, method, args) -> switch (method.getName()) {
                case "ensureVisible" -> {
                    if (!visible) { opens++; visible = true; mutationTick = tick; world.h.minecraft.screen = screen(); yield false; }
                    yield rendered && mutationTick != tick;
                }
                case "swapInventoryToHotbar" -> {
                    check(visible && rendered && mutationTick != tick, "swap must use a rendered GUI and a free native mutation tick");
                    mutationTick = tick; swaps++; source = (int) args[1]; hotbar = (int) args[2];
                    check(source == 12 && hotbar == 4, "swap must use the actual empty main slot and current selected hotbar entry");
                    swap = new MenuReceipt(MenuReceipt.Kind.SWAP_TO_HOTBAR, (LocalPlayerContext) args[0], 0, 0, 20, false, null); yield swap;
                }
                case "close" -> { check(visible && mutationTick != tick, "close cannot share the swap tick"); mutationTick = tick; closes++;
                    close = new MenuReceipt(MenuReceipt.Kind.CLOSE, (LocalPlayerContext) args[0], 0, 0, 20, true, null); yield close; }
                case "poll" -> {
                    if (args[1] == swap && allowSwap && tick > swap.submittedTick() && !swap.terminal()) {
                        ItemStack parked = world.inventory.getItem(hotbar); world.inventory.setItem(hotbar, world.inventory.getItem(source)); world.inventory.setItem(source, parked);
                        swap.finish(MenuReceipt.Status.CONFIRMED_APPLIED, "native swap fixture confirmed");
                    }
                    if (args[1] == close && allowClose && tick > close.submittedTick() && !close.terminal()) {
                        visible = false; world.h.minecraft.screen = null; close.finish(MenuReceipt.Status.CONFIRMED_APPLIED, "native close fixture confirmed");
                    }
                    yield args[1];
                }
                default -> throw new AssertionError("unexpected menu action " + method.getName());
            });
            context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(), new Class<?>[]{LocalPlayerContext.class}, (proxy, method, args) -> switch (method.getName()) {
                case "player" -> world.player;
                case "minecraft" -> world.h.minecraft;
                case "menus" -> menus;
                case "mutationAvailable" -> mutationTick != tick;
                case "bodyEpoch", "controlRevision" -> 1L;
                case "tickRevision" -> tick;
                default -> throw new AssertionError("unexpected context access " + method.getName());
            });
        }
        Screen screen() throws Exception { var screen = world.h.allocate(MenuVisibility.PlayerInventoryScreen.class); ActorControlTestHarness.field(AbstractContainerScreen.class, "menu").set(screen, world.player.inventoryMenu); return screen; }
        MachineMenuHandParking.Status step(MachineMenuHandParking parking) { tick++; return parking.tick(context); }
    }
    private static boolean same(ItemStack a, ItemStack b) { return a.getCount() == b.getCount() && ItemStack.isSameItemSameComponents(a, b); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
