// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuPort;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import sun.misc.Unsafe;

/** Real session and voxel rays; only the asynchronous native close boundary is supplied by a port. */
public final class Ae2InPlaceSupplyTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        try (var world = new InteractionWorldTestHarness()) {
            field(AbstractContainerMenu.class, "carried").set(world.player.inventoryMenu, ItemStack.EMPTY);
            var bridge = (Ae2ReflectionBridge) memory.allocateInstance(Ae2ReflectionBridge.class);
            field(Ae2ReflectionBridge.class, "storageMenuClass").set(bridge, InventoryMenu.class);
            var request = new Ae2ResourceSupply.Request(List.of(new Ae2ResourceSupply.Group(
                    ResourceLocation.parse("minecraft:water_bucket"), 1)), false);
            var target = new Ae2TerminalAccess.FixedTarget("test", new BlockPos(0, 2, 1),
                    Direction.SOUTH, world.player.blockPosition(), new Vec3(.5, 2.5, 1.95));
            world.set(target.position(), Blocks.STONE.defaultBlockState());
            check(Ae2SupplySession.inPlaceHit(world.player, target) != null,
                    "current eye can reach the actual terminal-facing native outline");
            world.set(new BlockPos(0, 2, 2), Blocks.STONE.defaultBlockState());
            check(Ae2SupplySession.inPlaceHit(world.player, target) == null,
                    "an intervening native block excludes fixed access");
            world.set(new BlockPos(0, 2, 2), Blocks.AIR.defaultBlockState());
            world.position(new Vec3(.5, 10, 3.5));
            check(Ae2SupplySession.inPlaceHit(world.player, target) == null,
                    "falling away from a terminal cannot retain a stale reachable hit");
            world.position(new Vec3(.5, 1, 3.5));
            var direct = new Ae2SupplySession(world.player, request, bridge, true);
            field(Ae2SupplySession.class, "fixedCandidates").set(direct, List.of(target));
            var prepare = Ae2SupplySession.class.getDeclaredMethod("prepareFixedAccess", LocalPlayerContext.class);
            prepare.setAccessible(true); prepare.invoke(direct, ClientRuntime.requireContext(world.player));
            check(direct.phase().equals("face_fixed") && field(Ae2SupplySession.class, "navigation").get(direct) == null,
                    "in-place terminal access never invokes fixed-terminal navigation");
            NonNullList<Slot> slots = NonNullList.create();
            for (int slot = 0; slot < 36; slot++) slots.add(new Slot(world.inventory, slot, 0, 0));
            field(AbstractContainerMenu.class, "slots").set(world.player.inventoryMenu, slots);
            world.inventory.setItem(17, new ItemStack(Items.COBWEB, 2));
            check(destination(direct, world, new ItemStack(Items.COBWEB)) == 0,
                    "reflex extraction chooses an empty hotbar before merging into storage inventory");
            var regular = new Ae2SupplySession(world.player, request, bridge);
            check(destination(regular, world, new ItemStack(Items.COBWEB)) == 17,
                    "ordinary AE supply retains its existing stack-merging preference");
            world.inventory.setItem(17, ItemStack.EMPTY);
            check(world.inventory.add(new ItemStack(Items.WATER_BUCKET))
                            && world.inventory.getItem(0).is(Items.WATER_BUCKET),
                    "the native Player.addItem fluid-fill destination uses the first free hotbar slot");
            world.inventory.setItem(0, ItemStack.EMPTY);

            var session = new Ae2SupplySession(world.player, request, bridge, true);
            var screen = (InventoryScreen) memory.allocateInstance(InventoryScreen.class);
            field(AbstractContainerScreen.class, "menu").set(screen, world.player.inventoryMenu);
            ClientRuntime.requireContext(world.player).minecraft().screen = screen;
            field(AbstractContainerMenu.class, "carried").set(world.player.inventoryMenu, new ItemStack(Items.WATER_BUCKET));
            int[] closes = {0}; boolean[] confirm = {false}; MenuReceipt[] receipt = {null};
            MenuPort menus = (MenuPort) Proxy.newProxyInstance(MenuPort.class.getClassLoader(), new Class<?>[]{MenuPort.class},
                    (proxy, method, values) -> {
                        if (method.getName().equals("closeForTaskBoundary")) {
                            closes[0]++;
                            var constructor = MenuReceipt.class.getDeclaredConstructor(MenuReceipt.Kind.class,
                                    LocalPlayerContext.class, int.class, int.class, int.class, boolean.class, MenuConfirmation.class);
                            constructor.setAccessible(true);
                            receipt[0] = constructor.newInstance(MenuReceipt.Kind.CLOSE, values[0], 0, 0, 20, true,
                                    MenuConfirmation.closedToInventory());
                            return receipt[0];
                        }
                        if (method.getName().equals("poll")) {
                            if (confirm[0]) {
                                world.inventory.setItem(14, new ItemStack(Items.WATER_BUCKET));
                                field(AbstractContainerMenu.class, "carried").set(world.player.inventoryMenu, ItemStack.EMPTY);
                                ClientRuntime.requireContext(world.player).minecraft().screen = null;
                                field(MenuReceipt.class, "status").set(receipt[0], MenuReceipt.Status.CONFIRMED_APPLIED);
                            }
                            return receipt[0];
                        }
                        throw new AssertionError("cleanup must not submit an AE extraction or restoration: " + method.getName());
                    });
            check(session.finishInPlace(context(world, menus), "fall deadline").isEmpty(),
                    "submitting close does not make cleanup complete");
            world.nextTick();
            check(session.finishInPlace(context(world, menus), "fall deadline").isEmpty() && closes[0] == 1,
                    "pending close is polled once and never resubmitted");
            confirm[0] = true; world.nextTick();
            check(session.finishInPlace(context(world, menus), "fall deadline").isPresent()
                            && world.player.containerMenu.getCarried().isEmpty()
                            && world.inventory.getItem(14).is(Items.WATER_BUCKET) && closes[0] == 1,
                    "native cursor return and close settle before handoff");
        }
        System.out.println("Ae2InPlaceSupplyTest: passed");
    }
    private static LocalPlayerContext context(InteractionWorldTestHarness world, MenuPort menus) {
        var current = ClientRuntime.requireContext(world.player);
        return (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(),
                new Class<?>[]{LocalPlayerContext.class}, (proxy, method, values) ->
                        method.getName().equals("menus") ? menus : method.invoke(current, values));
    }
    private static int destination(Ae2SupplySession session, InteractionWorldTestHarness world, ItemStack sample)
            throws Exception {
        var choose = Ae2SupplySession.class.getDeclaredMethod("exactDestination", AbstractContainerMenu.class, ItemStack.class);
        choose.setAccessible(true);
        Object selected = choose.invoke(session, world.player.inventoryMenu, sample);
        var slot = selected.getClass().getDeclaredMethod("inventorySlot"); slot.setAccessible(true);
        return (Integer) slot.invoke(selected);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
