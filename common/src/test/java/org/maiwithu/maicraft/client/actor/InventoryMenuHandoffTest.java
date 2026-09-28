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

/** 普通背包的交接必须先清空鼠标和合成格，再等实际关闭；不会把其他界面当作空闲背包。 */
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
    }
    private static void machineClose(String kind) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            inventory(h);
            if (kind.equals("cursor")) h.player.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND));
            if (kind.equals("craft")) h.player.inventoryMenu.getSlot(1).set(new ItemStack(Items.OAK_PLANKS));
            if (kind.equals("foreign")) h.h.minecraft.screen = h.h.allocate(PauseScreen.class);
            boolean idle = kind.equals("idle");
            check(MenuVisibility.idlePlayerInventory(h.h.minecraft, h.player) == idle, "only the settled ordinary inventory is eligible");
            var task = new MachineMenuCloseTask(h.player, new MachineMenuCloseTaskRecord("handoff", 1000));
            var state = task.tick(h.player);
            if (idle) {
                check(state == TaskState.RUNNING && h.h.minecraft.screen != null, "submitting close is not completed handoff");
                for (int i = 0; i < 12 && !state.isTerminal(); i++) {
                    MenuVisibility.rendered(h.h.minecraft.screen); h.nextTick(); state = task.tick(h.player);
                }
                check(state == TaskState.SUCCESS && h.h.minecraft.screen == null, "close waits for native visibility and final world state");
            } else check(state == TaskState.FAILED && h.h.minecraft.screen != null
                    && Boolean.FALSE.equals(task.result(state).data().get("effects_started")), "busy or foreign screens receive no close operation");
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
                    && session.phase().equals("wait_initial_inventory_close"), "AE waits on inventory close instead of declaring stock unavailable");
            var receipt = ActorControlTestHarness.field(sessionType, "menuReceipt").get(session);
            session.tick(ClientRuntime.requireContext(h.player));
            check(receipt == ActorControlTestHarness.field(sessionType, "menuReceipt").get(session), "one pending close is not resubmitted");
            for (int i = 0; i < 12 && !session.phase().equals("start"); i++) {
                MenuVisibility.rendered(h.h.minecraft.screen); h.nextTick(); session.tick(ClientRuntime.requireContext(h.player));
            }
            check(session.phase().equals("start") && session.outcome().isEmpty() && h.h.minecraft.screen == null
                    && ActorControlTestHarness.field(sessionType, "request").get(session) == request,
                    "the unchanged acquisition resumes only after verified world access");
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
