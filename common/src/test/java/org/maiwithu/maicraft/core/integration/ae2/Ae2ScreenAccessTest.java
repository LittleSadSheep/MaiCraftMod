package org.maiwithu.maicraft.core.integration.ae2;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuPort;
import sun.misc.Unsafe;

/** Exercise the real AE session entry/cleanup branches without an AE network or native window. */
public final class Ae2ScreenAccessTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        Minecraft minecraft = (Minecraft) memory.allocateInstance(Minecraft.class);
        LocalPlayer player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
        ClientLevel level = (ClientLevel) memory.allocateInstance(ClientLevel.class);
        field(Level.class, "dimension").set(level, Level.OVERWORLD);
        field(LocalPlayer.class, "level").set(player, level);
        field(LocalPlayer.class, "blockPosition").set(player, BlockPos.ZERO);
        field(LocalPlayer.class, "inventory").set(player, new Inventory(player));
        InventoryMenu inventory = (InventoryMenu) memory.allocateInstance(InventoryMenu.class);
        field(AbstractContainerMenu.class, "carried").set(inventory, ItemStack.EMPTY);
        field(LocalPlayer.class, "inventoryMenu").set(player, inventory);
        player.containerMenu = inventory;
        Ae2ReflectionBridge bridge = (Ae2ReflectionBridge) memory.allocateInstance(Ae2ReflectionBridge.class);
        field(Ae2ReflectionBridge.class, "storageMenuClass").set(bridge, String.class);
        var request = new Ae2ResourceSupply.Request(List.of(new Ae2ResourceSupply.Group(
                ResourceLocation.parse("minecraft:water_bucket"), 1)), false);
        int[] openings = {0};
        MenuPort menus = (MenuPort) Proxy.newProxyInstance(MenuPort.class.getClassLoader(),
                new Class<?>[]{MenuPort.class}, (proxy, method, values) -> {
                    if (method.getName().equals("ensureVisible")) { openings[0]++; return false; }
                    throw new AssertionError("must not close or operate an unrelated GUI: " + method.getName());
                });
        LocalPlayerContext context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(),
                new Class<?>[]{LocalPlayerContext.class}, (proxy, method, values) -> switch (method.getName()) {
                    case "minecraft" -> minecraft;
                    case "player" -> player;
                    case "menus" -> menus;
                    default -> throw new AssertionError("unexpected actor access: " + method.getName());
                });
        minecraft.screen = (ChatScreen) memory.allocateInstance(ChatScreen.class);
        var chat = new Ae2SupplySession(player, request, bridge);
        invoke(chat, "start", context);
        check(chat.outcome().isEmpty() && chat.phase().equals("discover_fixed"),
                "without a wireless item or remembered terminal, chat starts bounded fixed-terminal discovery");
        minecraft.screen = (PauseScreen) memory.allocateInstance(PauseScreen.class);
        var modal = new Ae2SupplySession(player, request, bridge);
        invoke(modal, "start", context);
        check(modal.outcome().orElseThrow().code().equals("screen_open"), "unrelated dialogs remain protected");
        minecraft.screen = (ChatScreen) memory.allocateInstance(ChatScreen.class);
        var cleanup = new Ae2SupplySession(player, request, bridge);
        invoke(cleanup, "cleanClose", context);
        check(cleanup.phase().equals("clean_restore"), "chat needs no native menu close");
        Class<?> swapClass = Class.forName(Ae2SupplySession.class.getName() + "$InventorySwap");
        var constructor = swapClass.getDeclaredConstructor(int.class, int.class, ItemStack.class, ItemStack.class);
        constructor.setAccessible(true);
        field(Ae2SupplySession.class, "inventorySwap").set(cleanup,
                constructor.newInstance(9, 0, ItemStack.EMPTY, ItemStack.EMPTY));
        invoke(cleanup, "cleanRestore", context);
        check(openings[0] == 1 && cleanup.outcome().isEmpty(), "restore from chat still awaits its real inventory GUI");
        field(Ae2ReflectionBridge.class, "storageMenuClass").set(bridge, InventoryMenu.class);
        minecraft.screen = (PauseScreen) memory.allocateInstance(PauseScreen.class);
        var ownership = Ae2SupplySession.class.getDeclaredMethod("ownsOpenMenu", LocalPlayerContext.class);
        ownership.setAccessible(true);
        check(!(Boolean) ownership.invoke(cleanup, context), "an AE menu hidden by an unrelated screen is not owned visibly");
        System.out.println("Ae2ScreenAccessTest: passed");
    }
    private static void invoke(Ae2SupplySession session, String name, LocalPlayerContext context) throws Exception {
        var method = Ae2SupplySession.class.getDeclaredMethod(name, LocalPlayerContext.class);
        method.setAccessible(true);
        method.invoke(session, context);
    }
    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
