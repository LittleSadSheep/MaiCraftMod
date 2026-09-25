// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;

/** 精确准备归属明确的背包材料；Create 按物品类型消耗链条，因此拒绝携带特殊数据组件的链条。 */
final class ChainConveyorInventory {
    private final FirstPersonActionGate selection = new FirstPersonActionGate();
    private int source = -1;
    static boolean plain(ItemStack stack) { return !stack.isEmpty() && stack.is(Items.CHAIN) && stack.getComponentsPatch().isEmpty(); }
    static int mainCount(LocalPlayer player) {
        return player.getInventory().items.stream().filter(stack -> stack.is(Items.CHAIN)).mapToInt(ItemStack::getCount).sum();
    }
    static int count(LocalPlayer player) {
        return mainCount(player) + (player.getOffhandItem().is(Items.CHAIN) ? player.getOffhandItem().getCount() : 0);
    }
    static void requirePlainChains(LocalPlayer player) {
        for (ItemStack stack : player.getInventory().items)
            if (stack.is(Items.CHAIN) && !plain(stack)) throw new IllegalArgumentException("chain_conveyor_special_chain_components_present");
        if (player.getOffhandItem().is(Items.CHAIN) && !plain(player.getOffhandItem()))
            throw new IllegalArgumentException("chain_conveyor_special_chain_components_present");
    }
    boolean select(LocalPlayerContext context) {
        if (source < 0) {
            for (int slot = 0; slot < context.player().getInventory().items.size(); slot++)
                if (plain(context.player().getInventory().getItem(slot))) { source = slot; break; }
            if (source < 0) throw new IllegalArgumentException("chain_conveyor_main_hand_chain_missing");
        }
        var state = selection.select(context.player(), source);
        if (state == FirstPersonActionGate.Status.FAILED) throw new IllegalStateException("chain_conveyor_inventory_selection_failed: " + selection.failure());
        if (state != FirstPersonActionGate.Status.READY) return false;
        if (!plain(context.player().getMainHandItem())) throw new IllegalArgumentException("chain_conveyor_selected_stack_changed");
        return true;
    }
    // 链条已经按原生动作消耗后，直接释放选择器；不为恢复背包原排序额外交换物品或切换手持格。
    void finish() { selection.reset(); }
}
