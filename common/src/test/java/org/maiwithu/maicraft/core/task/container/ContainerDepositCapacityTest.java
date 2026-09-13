// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.container;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.task.TaskState;

/** Plans a real vanilla slot layout without clicking; capacity and cursor preservation remain independent of transfer-tail code. */
public final class ContainerDepositCapacityTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            h.nextTick(); h.inventory.setItem(0, new ItemStack(Items.DIRT, 10));
            ChestMenu menu = ChestMenu.threeRows(7, h.inventory);
            for (int slot = 0; slot < 27; slot++) menu.getSlot(slot).set(new ItemStack(Items.STONE, 64));
            menu.getSlot(0).set(new ItemStack(Items.DIRT, 59));
            h.player.containerMenu = menu; Minecraft.getInstance().screen = new ContainerScreen(menu, h.inventory, Component.literal("capacity fixture"));
            var record = SemanticContainerTaskRecord.depositAvailableAt("spoil-capacity", 1000, ResourceLocation.parse("minecraft:dirt"), 10,
                    new BlockPos(3, 1, 3), ResourceLocation.parse("minecraft:barrel"), List.of());
            var task = new SemanticContainerCompanionTask(h.player, record);
            Class<?> view = java.util.Arrays.stream(task.getClass().getDeclaredClasses()).filter(type -> type.getSimpleName().equals("MenuView")).findFirst().orElseThrow();
            var constructor = view.getDeclaredConstructors()[0]; constructor.setAccessible(true);
            set(task, "view", constructor.newInstance(menu, java.util.stream.IntStream.range(27, 63).boxed().toList(),
                    java.util.stream.IntStream.range(0, 27).boxed().toList(), true, false));
            set(task, "expectedContainerId", 7); set(task, "expectedMenuClass", menu.getClass()); set(task, "ownedMenu", menu);
            var fingerprint = task.getClass().getDeclaredMethod("fingerprint", net.minecraft.world.inventory.AbstractContainerMenu.class); fingerprint.setAccessible(true);
            set(task, "stableFingerprint", fingerprint.invoke(null, menu));
            var plan = task.getClass().getDeclaredMethod("plan"); plan.setAccessible(true);
            check(plan.invoke(task) == TaskState.RUNNING && ((Number) get(task, "plannedAmount")).intValue() == 5,
                    "internal spoil deposit must fit the actual five available matching slots without requiring all ten items to fit");
            check(menu.getSlot(0).getItem().getCount() == 59 && h.inventory.getItem(0).getCount() == 10, "capacity planning cannot mutate either inventory");
            menu.setCarried(new ItemStack(Items.DIRT)); set(task, "openedMenu", true); set(task, "openRequested", true);
            var cleanup = task.getClass().getDeclaredMethod("cleanupMenu"); cleanup.setAccessible(true); cleanup.invoke(task); task.result(TaskState.FAILED);
            check(h.player.containerMenu == menu && menu.getCarried().getCount() == 1 && h.blockUses() == 0 && h.itemUses() == 0,
                    "unconfirmed cursor and its GUI are preserved instead of closing into a potentially full inventory");
        }
    }
    private static void set(Object target, String name, Object value) throws Exception { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    private static Object get(Object target, String name) throws Exception { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
