package org.maiwithu.maicraft.core.integration.create.elevator;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuPort;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import sun.misc.Unsafe;

/**
 * 模拟遥控器换栏和关闭界面，检查未确认时不重复交换、外部新界面不被认领，结束时原排列被恢复。
 */
public final class ElevatorRemoteStagingTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        Minecraft minecraft = (Minecraft) memory.allocateInstance(Minecraft.class);
        LocalPlayer player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
        field(LocalPlayer.class, "inventory").set(player, new Inventory(player));
        field(LocalPlayer.class, "inventoryMenu").set(player, memory.allocateInstance(InventoryMenu.class));
        player.containerMenu = player.inventoryMenu;
        player.getInventory().setItem(9, new ItemStack(Items.STICK));
        player.getInventory().setItem(0, new ItemStack(Items.APPLE, 2));
        InventoryScreen screen = (InventoryScreen) memory.allocateInstance(InventoryScreen.class);
        var ctor = MenuReceipt.class.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        MenuReceipt[] receipt = new MenuReceipt[1]; int[] swaps = {0};
        LocalPlayerContext[] context = new LocalPlayerContext[1];
        MenuPort menus = (MenuPort) Proxy.newProxyInstance(MenuPort.class.getClassLoader(), new Class<?>[]{MenuPort.class}, (p,m,a) -> {
            if (m.getName().equals("ensureVisible")) { minecraft.screen = screen; return true; }
            if (m.getName().equals("poll")) return a[1];
            if (m.getName().equals("swapInventoryToHotbar")) {
                int source = (int) a[1], target = (int) a[2];
                ItemStack before = player.getInventory().getItem(source);
                player.getInventory().setItem(source, player.getInventory().getItem(target));
                player.getInventory().setItem(target, before); swaps[0]++;
            } else if (m.getName().equals("close")) minecraft.screen = null;
            else throw new AssertionError("unexpected menu operation " + m.getName());
            return receipt[0] = (MenuReceipt) ctor.newInstance(MenuReceipt.Kind.CLICK, context[0], 0, 0, 30, true, null);
        });
        context[0] = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(), new Class<?>[]{LocalPlayerContext.class}, (p,m,a) -> switch (m.getName()) {
            case "minecraft" -> minecraft; case "player" -> player; case "menus" -> menus;
            case "mutationAvailable" -> true; case "bodyEpoch", "controlRevision", "tickRevision" -> 1L;
            default -> throw new AssertionError("unexpected context request " + m.getName());
        });
        var staging = new ElevatorRemoteStaging(); var actions = new ElevatorActions();
        check(!staging.ready(context[0], 9, actions) && swaps[0] == 1, "initial controller swap missing");
        check(staging.ownsScreen(context[0]), "owned inventory was not allowed to finish staging");
        InventoryScreen unrelated = (InventoryScreen) memory.allocateInstance(InventoryScreen.class);
        minecraft.screen = unrelated;
        check(!staging.ownsScreen(context[0]), "a replacement user inventory was claimed as ours");
        minecraft.screen = screen;
        check(!staging.cleanup(context[0], actions) && swaps[0] == 1, "cancel repeated an unconfirmed swap");
        confirm(receipt[0]);
        check(!staging.cleanup(context[0], actions) && swaps[0] == 2, "confirmed swap was not reversed once");
        confirm(receipt[0]);
        check(!staging.cleanup(context[0], actions), "native close must also settle");
        confirm(receipt[0]);
        check(staging.cleanup(context[0], actions) && minecraft.screen == null && swaps[0] == 2,
                "cleanup did not finish with a closed inventory");
        check(player.getInventory().getItem(9).is(Items.STICK) && player.getInventory().getItem(0).is(Items.APPLE)
                && player.getInventory().getItem(0).getCount() == 2, "original inventory arrangement was not restored");
        System.out.println("ElevatorRemoteStagingTest: passed");
    }
    private static void confirm(MenuReceipt receipt) throws Exception {
        var finish = MenuReceipt.class.getDeclaredMethod("finish", MenuReceipt.Status.class, String.class); finish.setAccessible(true);
        finish.invoke(receipt, MenuReceipt.Status.CONFIRMED_APPLIED, "synchronized slots");
    }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { var field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
