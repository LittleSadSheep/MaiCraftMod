// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.Map;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.DefaultBodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/** 快捷栏全满时显示背包，把手持工具原样移到真实空格，腾出空手去开木桶或机器；不丢物品。 */
public final class MachineMenuHandParking {
    public enum Status { RUNNING, READY, FAILED }
    private int source = -1, hotbar;
    private ItemStack before;
    private AbstractContainerMenu inventory;
    private Screen ownedScreen;
    private MenuReceipt swap, closing;
    private boolean swapped, ready, uncertain;
    private String failure;

    public Status tick(LocalPlayerContext context) {
        if (failure != null) return Status.FAILED;
        if (ready) return Status.READY;
        var player = context.player();
        if (player.containerMenu != player.inventoryMenu || !clearCursorAndGrid(player.inventoryMenu)) return failed("machine_menu_inventory_busy");
        if (source < 0) {
            // 外来界面和鼠标残留不能借用；没有空主背包格就停下，不清空手中工具来制造空间。
            if (!DefaultBodyControlPort.permitsWorldMovement(context.minecraft().screen)) return failed("machine_menu_foreign_inventory_screen");
            for (int slot = 9; slot < Math.min(36, player.getInventory().items.size()); slot++)
                if (player.getInventory().getItem(slot).isEmpty()) { source = slot; break; }
            if (source < 0) return failed("machine_menu_empty_hand_required");
            hotbar = player.getInventory().selected; before = player.getInventory().getItem(hotbar).copy(); inventory = player.inventoryMenu;
        }
        if (inventory != player.inventoryMenu || player.getInventory().selected != hotbar) return failed("machine_menu_hand_selection_changed");
        if (swap != null && !swapped) {
            // 等游戏确认交换，并核对工具数量、名称、耐久等组件都保留在原定空格，才算手真的空了。
            swap = context.menus().poll(context, swap);
            if (!swap.terminal()) return Status.RUNNING;
            if (swap.status() != MenuReceipt.Status.CONFIRMED_APPLIED) { uncertain = true; return failed("machine_menu_hand_park_unconfirmed"); }
            if (!player.getMainHandItem().isEmpty() || !same(player.getInventory().getItem(source), before)) {
                uncertain = true; return failed("machine_menu_hand_park_changed");
            }
            swapped = true; return Status.RUNNING;
        }
        if (closing != null) {
            closing = context.menus().poll(context, closing);
            if (!closing.terminal()) return Status.RUNNING;
            if (closing.status() != MenuReceipt.Status.CONFIRMED_APPLIED || context.minecraft().screen != null) return failed("machine_menu_hand_screen_close_unconfirmed");
            ready = true; return Status.READY;
        }
        if (ownedScreen != null && context.minecraft().screen != ownedScreen) return failed("machine_menu_hand_screen_changed");
        if (swapped) {
            if (!context.mutationAvailable()) return Status.RUNNING;
            closing = context.menus().close(context, 20); return Status.RUNNING;
        }
        if (!player.getInventory().getItem(source).isEmpty() || !same(player.getInventory().getItem(hotbar), before)) return failed("machine_menu_hand_parking_slots_changed");
        boolean visible = context.menus().ensureVisible(context);
        // 观众需要看见这次背包整理；只认本任务打开的背包画面，不能把已有外来界面据为己有。
        if (ownedScreen == null && context.minecraft().screen instanceof MenuVisibility.PlayerInventoryScreen
                && MenuVisibility.matches(context.minecraft(), inventory)) ownedScreen = context.minecraft().screen;
        if (!visible) return Status.RUNNING;
        if (ownedScreen == null || context.minecraft().screen != ownedScreen) return failed("machine_menu_hand_inventory_not_owned");
        if (!context.mutationAvailable()) return Status.RUNNING;
        swap = context.menus().swapInventoryToHotbar(context, source, hotbar, 20);
        return Status.RUNNING;
    }
    public boolean started() { return source >= 0; }
    public boolean settling() { return !ready && (ownedScreen != null || swap != null); }
    public String failure() { return failure; }
    public boolean uncertain() { return uncertain; }
    public Map<String, Object> evidence() { return Map.of("inventory_parking_used", swap != null, "hand_stack_preserved", swapped,
            "inventory_screen_closed", ready, "outcome_uncertain", uncertain); }
    public void cleanup(LocalPlayer player) {
        // 取消或失败时只关闭自己打开且鼠标、合成格都空的背包；工具留在已经确认放入的位置。
        if (ready || ownedScreen == null) return;
        if (swap != null && !swapped) uncertain = true;
        try {
            var context = ClientRuntime.requireContext(player);
            if (context.minecraft().screen == ownedScreen && player.containerMenu == inventory && clearCursorAndGrid(inventory))
                context.menus().closeForTaskBoundary(context, 20, "owned empty-hand inventory preparation ended");
        } catch (RuntimeException unavailable) { /* 身体控制已撤销时，由原有角色边界处理收尾，不碰新角色界面。 */ }
    }
    private Status failed(String code) { failure = code; uncertain |= swap != null || closing != null; return Status.FAILED; }
    private static boolean clearCursorAndGrid(AbstractContainerMenu menu) {
        if (!menu.getCarried().isEmpty() || menu.slots == null || menu.slots.size() < 5) return false;
        for (int slot = 1; slot <= 4; slot++) if (!menu.getSlot(slot).getItem().isEmpty()) return false;
        return true;
    }
    private static boolean same(ItemStack left, ItemStack right) { return left.getCount() == right.getCount() && ItemStack.isSameItemSameComponents(left, right); }
}
