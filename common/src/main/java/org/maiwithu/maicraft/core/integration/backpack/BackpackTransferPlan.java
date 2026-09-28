// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.backpack;

import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.core.task.container.ContainerTransferTaskRecord;

/** 在已同步的本人背包内挑下一笔精确搬运，存取都只触及主存储与玩家三十六格。 */
public final class BackpackTransferPlan {
    public record Next(ContainerTransferTaskRecord.Move move, ResourceLocation item, String blocked) {}
    private BackpackTransferPlan() {}

    public static Next next(LocalPlayer player, BackpackMenuAccess.Snapshot view, boolean deposit,
                            Map<ResourceLocation, Integer> remaining) {
        var menu = view.menu();
        if (player.containerMenu != menu || !menu.getCarried().isEmpty()) return new Next(null, null, "menu_or_cursor_changed");
        List<Integer> main = view.playerSlots().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
        List<Integer> sources = deposit ? main : view.storageSlots(), destinations = deposit ? view.storageSlots() : main;
        boolean foundSource = false, unsupportedSource = false;
        for (int sourceIndex : sources) {
            var source = menu.getSlot(sourceIndex); ItemStack stack = source.getItem();
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            int wanted = remaining.getOrDefault(id, 0);
            if (stack.isEmpty() || wanted <= 0 || !source.mayPickup(player)) continue;
            if (view.infiniteSlots().contains(sourceIndex)) { unsupportedSource = true; continue; }
            foundSource = true;
            // 先并入同组件的已有堆，再使用空格；一次只把最多一叠拿到鼠标上，不靠快速移动过量取物。
            for (boolean empty : new boolean[]{false, true}) for (int destinationIndex : destinations) {
                if (sourceIndex == destinationIndex || view.infiniteSlots().contains(destinationIndex)) continue;
                var destination = menu.getSlot(destinationIndex); ItemStack existing = destination.getItem();
                if (existing.isEmpty() != empty || !existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, stack)
                        || !destination.mayPlace(stack)) continue;
                int capacity = destination.getMaxStackSize(stack);
                if (!deposit) capacity = Math.min(capacity, stack.getMaxStackSize());
                int amount = Math.min(Math.min(wanted, stack.getCount()), Math.min(stack.getMaxStackSize(), Math.max(0, capacity - existing.getCount())));
                if (amount > 0) return new Next(new ContainerTransferTaskRecord.Move(sourceIndex, destinationIndex, amount), id, null);
            }
        }
        // 看到了无限槽但尚无对应扣物协议时，保留能力缺口，不能把它报成背包没有这件物品。
        return new Next(null, null, foundSource ? "inventory_full" : unsupportedSource ? "backpack_infinite_transfer_unverified" : "backpack_stock_insufficient");
    }
}
