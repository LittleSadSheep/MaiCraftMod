// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.ToIntFunction;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Real supply/session state machines with delayed native slot and menu receipts, without a GUI. */
public final class CreativeBuildSupplyMenuTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            var h = new Harness(world);
            world.inventory.setItem(8, new ItemStack(Items.DIAMOND, 12));
            Class<?> type = Class.forName("org.maiwithu.maicraft.core.task.build.CreativeBuildMaterialSupply");
            var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
            Object supply = constructor.newInstance();
            Method ensure = type.getDeclaredMethod("ensure", LocalPlayerContext.class, Item.class, ToIntFunction.class);
            Method release = type.getDeclaredMethod("releaseUnused", LocalPlayerContext.class, ToIntFunction.class);
            ensure.setAccessible(true); release.setAccessible(true);
            ToIntFunction<Item> needed = item -> item == Items.OAK_PLANKS ? 0 : 10;
            h.complete(ensure, supply, h.context, Items.OAK_PLANKS, needed);
            check(h.opens == 1 && h.closes == 1 && h.writes == 1, "first material uses one completed GUI transaction");
            check(world.inventory.getItem(0).is(Items.OAK_PLANKS), "first material appeared after native confirmation");
            for (int i = 0; i < 8; i++) h.complete(ensure, supply, h.context, Items.OAK_PLANKS, needed);
            check(h.opens == 1 && h.closes == 1 && h.writes == 1,
                    "reuse neither reopens inventory nor writes or clears another slot");
            h.complete(ensure, supply, h.context, Items.GLASS, needed);
            check(h.opens == 2 && h.closes == 2 && h.writes == 2,
                    "second material waits for its own menu close, despite the first session being closed");
            check(world.inventory.getItem(0).is(Items.OAK_PLANKS) && world.inventory.getItem(1).is(Items.GLASS),
                    "both useful materials remain retained until construction ends");
            h.complete(release, supply, h.context, (ToIntFunction<Item>) item -> Integer.MAX_VALUE);
            check(h.opens == 3 && h.closes == 3 && h.writes == 4,
                    "final cleanup clears both owned materials within one newly closed GUI session");
            check(world.inventory.getItem(0).isEmpty() && world.inventory.getItem(1).isEmpty(),
                    "cleanup completion requires confirmed empty slots");
            check(world.inventory.getItem(8).is(Items.DIAMOND) && world.inventory.getItem(8).getCount() == 12,
                    "cleanup leaves the player's original supplies untouched");
        }
        System.out.println("CreativeBuildSupplyMenuTest: passed");
    }

    private static final class Harness {
        final InteractionWorldTestHarness world;
        final LocalPlayerContext context;
        boolean visible;
        int opens, closes, writes, pendingSlot;
        long tick = 1, mutationTick = Long.MIN_VALUE;
        ItemStack pendingStack;
        NativeActionReceipt writeReceipt;
        MenuReceipt closeReceipt;

        Harness(InteractionWorldTestHarness world) {
            this.world = world;
            MenuPort menus = (MenuPort) Proxy.newProxyInstance(MenuPort.class.getClassLoader(),
                    new Class<?>[]{MenuPort.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "ensureVisible" -> {
                            if (visible) yield true;
                            opens++; visible = true; yield false; // a newly opened GUI must render first
                        }
                        case "close" -> {
                            check(visible, "cannot close a GUI that was not opened"); closes++;
                            closeReceipt = new MenuReceipt(MenuReceipt.Kind.CLOSE,
                                    (LocalPlayerContext) args[0], 0, 0, 20, true, null);
                            yield closeReceipt;
                        }
                        case "poll" -> {
                            check(args[1] == closeReceipt, "poll the same submitted menu receipt");
                            if (tick > closeReceipt.submittedTick()) {
                                visible = false;
                                closeReceipt.finish(MenuReceipt.Status.CONFIRMED_APPLIED, "closed on later tick");
                            }
                            yield closeReceipt;
                        }
                        default -> throw new AssertionError("unexpected menu operation: " + method.getName());
                    });
            NativeActionPort actions = (NativeActionPort) Proxy.newProxyInstance(NativeActionPort.class.getClassLoader(),
                    new Class<?>[]{NativeActionPort.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "creativeSetSlot" -> {
                            check(visible && mutationTick != tick, "creative writes need a visible GUI and free tick");
                            check(writeReceipt == null || writeReceipt.terminal(), "never overwrite a pending slot receipt");
                            mutationTick = tick; writes++; pendingSlot = (int) args[1];
                            pendingStack = ((ItemStack) args[2]).copy();
                            writeReceipt = new NativeActionReceipt(NativeActionReceipt.Kind.CREATIVE_SET_SLOT,
                                    (LocalPlayerContext) args[0], 30, 1, null, null, null);
                            yield writeReceipt;
                        }
                        case "poll" -> {
                            check(args[1] == writeReceipt, "poll the same submitted native receipt");
                            if (tick > writeReceipt.submittedTick()) {
                                world.inventory.setItem(pendingSlot, pendingStack.copy());
                                writeReceipt.finish(NativeActionReceipt.Status.CONFIRMED_APPLIED, "slot synchronized on later tick");
                            }
                            yield writeReceipt;
                        }
                        default -> throw new AssertionError("unexpected native operation: " + method.getName());
                    });
            context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(),
                    new Class<?>[]{LocalPlayerContext.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "player" -> world.player;
                        case "menus" -> menus;
                        case "actions" -> actions;
                        case "mutationAvailable" -> mutationTick != tick;
                        case "bodyEpoch", "controlRevision" -> 1L;
                        case "tickRevision" -> tick;
                        default -> throw new AssertionError("unexpected actor access: " + method.getName());
                    });
        }

        void complete(Method method, Object supply, Object... args) throws Exception {
            for (int attempts = 0; attempts < 16; attempts++) {
                tick++;
                String status = method.invoke(supply, args).toString();
                check(!status.equals("FAILED"), "supply operation unexpectedly failed");
                if (status.equals("READY")) {
                    check(!visible, "READY must wait for this operation's visible GUI to close");
                    check(writeReceipt == null || writeReceipt.terminal(), "READY cannot precede slot confirmation");
                    return;
                }
            }
            throw new AssertionError("supply did not complete within its bounded receipt sequence");
        }
    }

    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
