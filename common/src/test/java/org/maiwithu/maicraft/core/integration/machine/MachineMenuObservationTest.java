package org.maiwithu.maicraft.core.integration.machine;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.ClientActorBoundary;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import sun.misc.Unsafe;

/**
 * 构造原版箱子菜单检查只读观察：没有动作控制权也能读取，但无来源绑定就不能取得存取许可；结束后恢复替换过的客户端字段。
 */
public final class MachineMenuObservationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        Minecraft minecraft = (Minecraft) memory.allocateInstance(Minecraft.class);
        LocalPlayer player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
        minecraft.player = player;
        minecraft.level = (ClientLevel) memory.allocateInstance(ClientLevel.class);
        field(LocalPlayer.class, "clientLevel").set(player, minecraft.level);
        field(LocalPlayer.class, "level").set(player, minecraft.level);
        field(Minecraft.class, "gameThread").set(minecraft, Thread.currentThread());
        Inventory inventory = new Inventory(player);
        field(LocalPlayer.class, "inventory").set(player, inventory);
        ChestMenu menu = ChestMenu.threeRows(4, inventory);
        player.containerMenu = menu;
        minecraft.screen = new ContainerScreen(menu, inventory, Component.literal("Observation"));
        ClientActorBoundary actor = ClientRuntime.actor();
        Field global = field(Minecraft.class, "instance");
        Field client = field(ClientActorBoundary.class, "minecraft");
        Field body = field(ClientActorBoundary.class, "observedPlayer");
        Field epoch = field(ClientActorBoundary.class, "bodyEpoch");
        Field tick = field(ClientActorBoundary.class, "tickRevision");
        Object oldGlobal = global.get(null), oldClient = client.get(actor), oldBody = body.get(actor);
        long oldEpoch = epoch.getLong(actor), oldTick = tick.getLong(actor);
        try {
            global.set(null, minecraft); client.set(actor, minecraft); body.set(actor, player);
            epoch.setLong(actor, 7); tick.setLong(actor, 50);
            check(actor.activeContext().isEmpty(), "the MCP observation must be outside an actor lease");
            var result = MachineMenu.inspect(player);
            check(result.get("gui_visible").getAsBoolean() && result.getAsJsonArray("menu_entries").size() == 63,
                    "read the visible synchronized menu without requiring an action tick");
            check(!result.get("transfer_receipt_available").getAsBoolean(),
                    "an unbound menu must not receive transfer authority just because it is readable");
            var stamp = actor.observationStamp(player).orElseThrow();
            var inspection = new MachineMenu.Inspection(player, menu, null, stamp);
            check(inspection.bodyEpoch == 7 && inspection.expires == 650 && inspection.menu.get() == menu,
                    "the read-only receipt clock must use the bound body and current tick");
            check(actor.activeContext().isEmpty() && tick.getLong(actor) == 50,
                    "inspection must neither advance the actor nor create a mutation lease");
            componentVariantsRemainVisible(player, inventory, menu);
            body.set(actor, null);
            check(actor.observationStamp(player).isEmpty(), "an unobserved body must not borrow an old body's epoch");
            check(MachineMenu.inspect(player).getAsJsonArray("menu_entries").size() == 63,
                    "missing transfer authority must not hide read-only menu information");
        } finally {
            global.set(null, oldGlobal); client.set(actor, oldClient); body.set(actor, oldBody);
            epoch.setLong(actor, oldEpoch); tick.setLong(actor, oldTick);
        }
        System.out.println("MachineMenuObservationTest: passed");
    }

    private static void componentVariantsRemainVisible(LocalPlayer player, Inventory inventory, ChestMenu menu) {
        Slot original = menu.getSlot(0);
        menu.slots.set(0, new Slot(original.container, original.getContainerSlot(), original.x, original.y) {
            @Override public boolean mayPlace(ItemStack stack) { return stack.has(DataComponents.CUSTOM_NAME); }
        });
        try {
            ItemStack plain = new ItemStack(Items.DIRT);
            ItemStack accepted = plain.copy();
            accepted.set(DataComponents.CUSTOM_NAME, Component.literal("accepted variant"));
            inventory.setItem(0, plain); inventory.setItem(1, accepted);
            check(acceptable(player).equals(List.of("minecraft:dirt")),
                    "a rejected first variant must not hide a later accepted variant of the same item");
            inventory.setItem(0, accepted); inventory.setItem(1, plain); inventory.setItem(2, accepted.copy());
            check(acceptable(player).equals(List.of("minecraft:dirt")),
                    "reordering variants must preserve acceptance and report each item id once");
            inventory.setItem(0, plain); inventory.setItem(1, ItemStack.EMPTY); inventory.setItem(2, ItemStack.EMPTY);
            check(acceptable(player).isEmpty(), "an item id must not be listed when all carried variants are rejected");

            inventory.clearContent();
            var items = net.minecraft.core.registries.BuiltInRegistries.ITEM.stream()
                    .filter(item -> item != Items.AIR).limit(17).toList();
            for (int i = 0; i < items.size(); i++) {
                ItemStack stack = new ItemStack(items.get(i));
                stack.set(DataComponents.CUSTOM_NAME, Component.literal("accepted " + i));
                inventory.setItem(i, stack);
            }
            var ids = acceptable(player);
            check(ids.size() == 16 && ids.stream().distinct().count() == 16,
                    "component checks must retain the existing bounded list of distinct item ids");
        } finally {
            inventory.clearContent();
            menu.slots.set(0, original);
        }
    }

    private static List<String> acceptable(LocalPlayer player) {
        var result = MachineMenu.inspect(player);
        check(!result.get("transfer_receipt_available").getAsBoolean(),
                "reporting acceptable item variants cannot authorize an unbound menu transfer");
        return result.getAsJsonArray("menu_entries").get(0).getAsJsonObject()
                .getAsJsonArray("accepts_carried_item_ids").asList().stream().map(value -> value.getAsString()).toList();
    }

    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try {
                Field result = owner.getDeclaredField(name); result.setAccessible(true); return result;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
