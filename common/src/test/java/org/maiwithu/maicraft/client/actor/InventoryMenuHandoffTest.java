// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;
import org.maiwithu.maicraft.core.integration.machine.MachineMenuCloseTask;
import org.maiwithu.maicraft.core.integration.machine.MachineMenuCloseTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 背包和其他页面均原生退出，鼠标及合成余料返还后才接续同一操作；不由模型另开关页任务。 */
public final class InventoryMenuHandoffTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (String kind : List.of("idle", "cursor", "craft", "foreign")) machineClose(kind);
        aeWaitsForSameClose();
        System.out.println("InventoryMenuHandoffTest: passed");
    }
    private static void inventory(InteractionWorldTestHarness h) throws Exception {
        var contents = new SimpleContainer(5); NonNullList<Slot> slots = NonNullList.create();
        for (int i = 0; i < 5; i++) slots.add(new Slot(contents, i, 0, 0));
        ActorControlTestHarness.field(AbstractContainerMenu.class, "slots").set(h.player.inventoryMenu, slots);
        h.player.inventoryMenu.setCarried(ItemStack.EMPTY); h.h.minecraft.screen = h.h.inventoryScreen(); h.h.simulateMenuClose();
        // 无窗口夹具注入服务器原生返料同步，实际执行器不能用 setItem 或清空游标来伪造这一步。
        ActorControlTestHarness.field(h.player.getClass(), "menuClose").set(h.player, (Runnable) () -> {
            ItemStack cursor = h.player.inventoryMenu.getCarried();
            if (!cursor.isEmpty()) h.inventory.add(cursor.copy());
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            for (int i = 1; i <= 4; i++) {
                ItemStack input = h.player.inventoryMenu.getSlot(i).getItem();
                if (!input.isEmpty()) h.inventory.add(input.copy());
                h.player.inventoryMenu.getSlot(i).set(ItemStack.EMPTY);
            }
            h.player.containerMenu = h.player.inventoryMenu; h.h.minecraft.screen = null;
        });
    }
    private static void machineClose(String kind) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            inventory(h);
            if (kind.equals("cursor")) h.player.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND));
            if (kind.equals("craft")) h.player.inventoryMenu.getSlot(1).set(new ItemStack(Items.OAK_PLANKS));
            if (kind.equals("foreign")) h.h.minecraft.screen = new PauseScreen(true) {
                @Override public void onClose() { h.h.minecraft.screen = null; }
            };
            boolean idle = kind.equals("idle");
            check(MenuVisibility.idlePlayerInventory(h.h.minecraft, h.player) == idle, "only the settled ordinary inventory is eligible");
            var task = new MachineMenuCloseTask(h.player, new MachineMenuCloseTaskRecord("handoff", 1000));
            var state = task.tick(h.player);
            check(state == TaskState.RUNNING, "有余物或其他页面也先等待原生退出，而不是界面占用失败");
            for (int i = 0; i < 16 && !state.isTerminal(); i++) {
                MenuVisibility.rendered(h.h.minecraft.screen); h.nextTick(); state = task.tick(h.player);
            }
            check(state == TaskState.SUCCESS && h.h.minecraft.screen == null, "原生关闭和返料结清后完成请求");
            if (kind.equals("cursor")) check(h.inventory.countItem(Items.DIAMOND) == 1, "鼠标物品按关闭同步返还");
            if (kind.equals("craft")) check(h.inventory.countItem(Items.OAK_PLANKS) == 1, "合成余料按关闭同步返还");
        }
    }
    private static void aeWaitsForSameClose() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            inventory(h);
            // AE类型只用于隔离开包前的交接流程；本例不伪造真实网络或取出水桶。
            Class<?> bridgeType = Class.forName("org.maiwithu.maicraft.core.integration.ae2.Ae2ReflectionBridge");
            Object bridge = h.h.allocate(bridgeType);
            ActorControlTestHarness.field(bridgeType, "storageMenuClass").set(bridge, ChestMenu.class);
            Class<?> sessionType = Class.forName("org.maiwithu.maicraft.core.integration.ae2.Ae2SupplySession");
            var constructor = sessionType.getDeclaredConstructor(LocalPlayer.class, Ae2ResourceSupply.Request.class, bridgeType); constructor.setAccessible(true);
            var request = new Ae2ResourceSupply.Request(List.of(new Ae2ResourceSupply.Group(ResourceLocation.parse("minecraft:water_bucket"), 1)), false);
            var session = (Ae2ResourceSupply.Session) constructor.newInstance(h.player, request, bridge);
            check(session.tick(ClientRuntime.requireContext(h.player)).isEmpty()
                    && session.phase().equals("start"), "AE 在原请求内等待公共关页，不报无货或另开任务");
            var receipt = ActorControlTestHarness.field(DefaultMenuPort.class, "active").get(h.h.actor.menus());
            session.tick(ClientRuntime.requireContext(h.player));
            check(receipt == ActorControlTestHarness.field(DefaultMenuPort.class, "active").get(h.h.actor.menus()), "公共在途关闭不重复提交");
            for (int i = 0; i < 16 && h.h.actor.menus().hasPendingTransaction(); i++) {
                MenuVisibility.rendered(h.h.minecraft.screen); h.nextTick(); h.h.actor.menus().advance(ClientRuntime.requireContext(h.player));
                if (h.h.actor.menus().hasPendingTransaction()) session.tick(ClientRuntime.requireContext(h.player));
            }
            check(session.phase().equals("start") && session.outcome().isEmpty() && h.h.minecraft.screen == null
                    && !h.h.actor.menus().hasPendingTransaction()
                    && ActorControlTestHarness.field(sessionType, "request").get(session) == request,
                    "the unchanged acquisition resumes only after verified world access");
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
