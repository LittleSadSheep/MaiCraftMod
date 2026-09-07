// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import sun.misc.Unsafe;

/** Exercise reflected repository evidence and the production session's actual strategy selection. */
public final class Ae2LandingWaterFallbackTest {
    private static final ResourceLocation WEB = ResourceLocation.parse("minecraft:cobweb");
    private static final ResourceLocation WATER = ResourceLocation.parse("minecraft:water_bucket");
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        try (var world = new InteractionWorldTestHarness()) {
            field(AbstractContainerMenu.class, "carried").set(world.player.inventoryMenu, ItemStack.EMPTY);
            var screen = (InventoryScreen) memory.allocateInstance(InventoryScreen.class);
            field(AbstractContainerScreen.class, "menu").set(screen, world.player.inventoryMenu);
            ClientRuntime.requireContext(world.player).minecraft().screen = screen;
            var bridge = bridge(memory);
            var request = new Ae2ResourceSupply.Request(List.of(new Ae2ResourceSupply.Group(
                    WEB, List.of(WEB, WATER), 1, Ae2ResourceSupply.SelectionMode.SINGLE_VARIANT)), false);
            var bucket = new RawEntry(new ItemKey(ResourceLocation.parse("minecraft:bucket")), 1, 5, false);
            var fluid = new RawEntry(new FluidKey(ResourceLocation.parse("minecraft:water")), 2, 9000, false);
            Repository.values = List.of(bucket, fluid, new RawEntry(new ItemKey(WEB), 3, 1, false));
            var stock = observe(world, bridge, request);
            var plan = (Ae2SupplyPlanner.Plan) field(Ae2SupplySession.class, "plan").get(stock);
            check(stock.phase().equals("process_item") && !waterRoute(stock)
                            && plan.groups().getFirst().allocations().getFirst().itemId().equals(WEB),
                    "a stored rescue item outranks converting network fluid even when water is available");

            Repository.values = List.of(bucket, fluid, new RawEntry(new ItemKey(WEB), 3, 0, true));
            var fill = observe(world, bridge, request);
            check(fill.phase().equals("fill_water_bucket") && waterRoute(fill),
                    "craftable-only alternatives do not hide usable fluid and stored empty buckets");
            var locked = (Map<?, ?>) field(Ae2SupplySession.class, "lockedVariantByGroup").get(fill);
            check(WATER.equals(locked.get(WEB)), "water is bound to the caller's actual group identity");
            check(field(Ae2SupplySession.class, "craftingRequests").getInt(fill) == 0
                            && field(Ae2SupplySession.class, "craftingJobsSubmitted").getInt(fill) == 0,
                    "the native fluid fallback never creates an autocrafting job");
            world.inventory.setItem(12, new ItemStack(Items.WATER_BUCKET));
            @SuppressWarnings("unchecked") var receipts = (List<Map<String, Object>>)
                    field(Ae2SupplySession.class, "waterFillReceipts").get(fill);
            receipts.add(Map.of("native_fill_confirmed", true));
            var audit = Ae2SupplySession.class.getDeclaredMethod("finalAuditPasses"); audit.setAccessible(true);
            check((Boolean) audit.invoke(fill), "a water fill satisfies the original multi-candidate group's exact audit");
            world.inventory.setItem(12, ItemStack.EMPTY);
            check(!(Boolean) audit.invoke(fill), "fill evidence without the actual water bucket fails final audit");

            Repository.values = List.of(fluid);
            check(!waterRoute(observe(world, bridge, request)), "fluid alone does not invent an empty bucket");
            Repository.values = List.of(bucket, fluid);
            var webOnly = new Ae2ResourceSupply.Request(List.of(new Ae2ResourceSupply.Group(WEB, 1)), false);
            check(!waterRoute(observe(world, bridge, webOnly)), "an unsuitable water landing cannot be introduced by supply");
            Repository.pending = true;
            var pending = observe(world, bridge, request);
            check(pending.phase().equals("wait_repository") && pending.outcome().isEmpty() && !waterRoute(pending),
                    "pending synchronization is not missing stock or a confirmed filled bucket");
        } finally { Repository.pending = false; Repository.values = List.of(); }
        System.out.println("Ae2LandingWaterFallbackTest: passed");
    }
    private static Ae2SupplySession observe(InteractionWorldTestHarness world,
            Ae2ReflectionBridge bridge, Ae2ResourceSupply.Request request) throws Exception {
        var session = new Ae2SupplySession(world.player, request, bridge, true);
        world.nextTick(); session.tick(ClientRuntime.requireContext(world.player));
        world.nextTick(); session.tick(ClientRuntime.requireContext(world.player));
        return session;
    }
    private static boolean waterRoute(Ae2SupplySession session) throws Exception {
        return field(Ae2SupplySession.class, "waterBucketRoute").getBoolean(session);
    }
    private static Ae2ReflectionBridge bridge(Unsafe memory) throws Exception {
        var bridge = (Ae2ReflectionBridge) memory.allocateInstance(Ae2ReflectionBridge.class);
        field(Ae2ReflectionBridge.class, "storageMenuClass").set(bridge, InventoryMenu.class);
        field(Ae2ReflectionBridge.class, "itemKeyClass").set(bridge, ItemKey.class);
        field(Ae2ReflectionBridge.class, "fluidKeyClass").set(bridge, FluidKey.class);
        field(Ae2ReflectionBridge.class, "fluidBucketUnits").setLong(bridge, 1000L);
        method(bridge, "getClientRepo", Repository.class, "repository");
        method(bridge, "getLinkStatus", Repository.class, "link");
        method(bridge, "linkConnected", Repository.class, "connected");
        method(bridge, "getAllEntries", Repository.class, "entries");
        method(bridge, "entryGetWhat", RawEntry.class, "key");
        method(bridge, "entryGetSerial", RawEntry.class, "serial");
        method(bridge, "entryGetStoredAmount", RawEntry.class, "stored");
        method(bridge, "entryIsCraftable", RawEntry.class, "craftable");
        method(bridge, "keyGetId", Key.class, "id");
        field(Ae2ReflectionBridge.class, "keyToStack").set(bridge, ItemKey.class.getMethod("stack", int.class));
        return bridge;
    }
    private static void method(Ae2ReflectionBridge bridge, String field, Class<?> type, String name) throws Exception {
        field(Ae2ReflectionBridge.class, field).set(bridge, type.getMethod(name));
    }
    public interface Key { ResourceLocation id(); }
    public record ItemKey(ResourceLocation id) implements Key {
        public ItemStack stack(int count) { return new ItemStack(BuiltInRegistries.ITEM.get(id), count); }
    }
    public record FluidKey(ResourceLocation id) implements Key { }
    public record RawEntry(Key key, long serial, long stored, boolean craftable) { }
    public static final class Repository {
        static List<RawEntry> values = List.of(); static boolean pending;
        public static Object repository() { return pending ? null : new Repository(); }
        public static Object link() { return new Repository(); }
        public static boolean connected() { return true; }
        public static List<RawEntry> entries() { return values; }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
