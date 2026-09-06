package org.maiwithu.maicraft.core.integration.machine;

import java.lang.reflect.Field;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import org.maiwithu.maicraft.client.actor.ClientActorBoundary;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import sun.misc.Unsafe;

/** A normal MCP read between ticks must not need or create native-action authority. */
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
