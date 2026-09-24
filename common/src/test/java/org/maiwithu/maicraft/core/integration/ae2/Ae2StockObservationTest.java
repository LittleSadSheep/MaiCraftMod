// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import sun.misc.Unsafe;

/** 只读查询没有物品目标或合成许可；只有真实库存读取完成后，界面收尾才能报告成功。 */
public final class Ae2StockObservationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var request = new Ae2ResourceSupply.Request(List.of(), false, Ae2ResourceSupply.Operation.OBSERVE);
        check(request.totalCount() == 0 && request.acceptedItemIds().isEmpty(), "observation has no hidden transfer demand");
        try { new Ae2ResourceSupply.Request(List.of(), true, Ae2ResourceSupply.Operation.OBSERVE); throw new AssertionError("observation allowed crafting"); }
        catch (IllegalArgumentException expected) { /* 查询不能转为制造或领取物品。 */ }
        try { new Ae2ResourceSupply.Request(List.of(), false); throw new AssertionError("empty supply accepted"); }
        catch (IllegalArgumentException expected) { /* 原有供料契约仍要求明确数量。 */ }
        var singleton = Unsafe.class.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        Unsafe memory = (Unsafe) singleton.get(null);
        try (var world = new InteractionWorldTestHarness()) {
            var cursor = AbstractContainerMenu.class.getDeclaredField("carried"); cursor.setAccessible(true);
            cursor.set(world.player.inventoryMenu, ItemStack.EMPTY);
            var bridge = (Ae2ReflectionBridge) memory.allocateInstance(Ae2ReflectionBridge.class);
            var session = new Ae2SupplySession(world.player, request, bridge);
            var ready = Ae2SupplySession.class.getDeclaredMethod("stockRepositorySettled", int.class); ready.setAccessible(true);
            check(!(boolean) ready.invoke(session, 0), "the initial empty repository cannot settle immediately");
            for (int tick = 0; tick < 10; tick++) check(!(boolean) ready.invoke(session, 0), "wait for initial network entries");
            check(!(boolean) ready.invoke(session, 2000), "a later inventory page restarts the observation window");
            for (int tick = 0; tick < 19; tick++) check(!(boolean) ready.invoke(session, 2000), "wait while the directory is stabilizing");
            check((boolean) ready.invoke(session, 2000), "a stable synchronized directory becomes readable");
            var audit = Ae2SupplySession.class.getDeclaredMethod("finalAuditPasses"); audit.setAccessible(true);
            check(!(boolean) audit.invoke(session), "zero requested items do not turn an unread network into success");
            var observed = Ae2SupplySession.class.getDeclaredField("networkStockObserved"); observed.setAccessible(true); observed.setBoolean(session, true);
            check((boolean) audit.invoke(session), "a completed observation with a clean cursor can finish");
            // 模拟菜单先打开、连接通知后到：等待期内不能把这一帧判成断网，超过期限才进入失败收尾。
            var menu = new ConnectingMenu(); world.player.containerMenu = menu;
            var screen = (InventoryScreen) memory.allocateInstance(InventoryScreen.class);
            var shownMenu = AbstractContainerScreen.class.getDeclaredField("menu"); shownMenu.setAccessible(true); shownMenu.set(screen, menu);
            ClientRuntime.requireContext(world.player).minecraft().screen = screen;
            set(bridge, "storageMenuClass", ConnectingMenu.class);
            set(bridge, "getLinkStatus", ConnectingMenu.class.getMethod("getLinkStatus"));
            set(bridge, "linkConnected", PendingLink.class.getMethod("connected"));
            var waiting = new Ae2SupplySession(world.player, request, bridge);
            var phase = Ae2SupplySession.class.getDeclaredField("phase"); phase.setAccessible(true);
            for (Object value : phase.getType().getEnumConstants()) if (value.toString().equals("WAIT_REPOSITORY")) phase.set(waiting, value);
            set(waiting, "phaseTicks", 1);
            var read = Ae2SupplySession.class.getDeclaredMethod("waitRepository"); read.setAccessible(true); read.invoke(waiting);
            check(waiting.phase().equals("wait_repository"), "initial connection synchronization waits without reporting empty stock");
            var deadline = Ae2SupplySession.class.getDeclaredField("REPOSITORY_READY_TICKS"); deadline.setAccessible(true);
            set(waiting, "phaseTicks", deadline.getInt(null) + 1); read.invoke(waiting);
            check(!waiting.phase().equals("wait_repository"), "an unavailable connection eventually enters bounded failure cleanup");
            check(world.itemUses() == 0 && world.blockUses() == 0 && world.inventory.isEmpty(), "audit does not extract or craft items");
        }
        System.out.println("Ae2StockObservationTest: passed");
    }
    private static void set(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    public static final class PendingLink { public boolean connected() { return false; } }
    public static final class ConnectingMenu extends AbstractContainerMenu {
        ConnectingMenu() { super(null, 24); }
        public PendingLink getLinkStatus() { return new PendingLink(); }
        @Override public boolean stillValid(Player player) { return true; }
        @Override public ItemStack quickMoveStack(Player player, int slot) { throw new AssertionError("stock observation moved items"); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
