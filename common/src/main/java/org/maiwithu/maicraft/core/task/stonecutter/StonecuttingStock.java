// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.stonecutter;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.core.task.container.ContainerTransferTaskRecord.Move;

/** 冻结输入与产物的来源和数量；所有搬运使用真实菜单槽号，投入与取出的数量精确等于本次请求的切制次数。 */
final class StonecuttingStock {
    private static final int INPUT_SLOT = 0, RESULT_SLOT = 1;
    private final LocalPlayer player;
    private final ResourceLocation input, output;
    private final int count;
    private int inputBefore, outputBefore;

    private StonecuttingStock(LocalPlayer player, ResourceLocation input, ResourceLocation output, int count) {
        this.player = player;
        this.input = input;
        this.output = output;
        this.count = count;
        this.inputBefore = countOf(input);
        this.outputBefore = countOf(output);
    }

    static StonecuttingStock prepare(LocalPlayer player, ResourceLocation input, ResourceLocation output, int count) {
        var stock = new StonecuttingStock(player, input, output, count);
        if (stock.inputBefore < count) throw new IllegalArgumentException("stonecutting_input_material_missing");
        if (!stock.hasOutputSpace()) throw new IllegalArgumentException("stonecutting_inventory_space_required");
        return stock;
    }

    /** 投入数量精确等于切制次数；逐叠搬运到切石机输入格，绝不整堆快速移动超额材料。 */
    List<Move> loadMoves(AbstractContainerMenu menu) {
        if (!menu.getSlot(INPUT_SLOT).getItem().isEmpty()) throw new IllegalStateException("stonecutting_input_slot_occupied");
        // 走到切石机之前可能拾到同类材料；首次投料前重建本批基线，实际切制只按之后的消费与产物核验。
        inputBefore = countOf(input); outputBefore = countOf(output);
        var moves = new ArrayList<Move>();
        int remaining = count;
        for (int i = 0; i < 36 && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!isInput(stack)) continue;
            int take = Math.min(remaining, stack.getCount());
            moves.add(new Move(menuSlot(menu, i), INPUT_SLOT, take));
            remaining -= take;
        }
        if (remaining != 0) throw new IllegalStateException("stonecutting_input_inventory_changed");
        return moves;
    }

    Move returnInputMove() { return new Move(INPUT_SLOT, -1, 0); }

    boolean inputLoaded(AbstractContainerMenu menu) {
        ItemStack stack = menu.getSlot(INPUT_SLOT).getItem();
        return menu.getCarried().isEmpty() && isInput(stack) && stack.getCount() == count;
    }

    /** 点击确认的可观察后置条件：原版点击先本地预测，菜单版本号不会随之变化，背包产物增加才是可用证据。 */
    int outputCount() { return countOf(output); }

    /** 本次请求造成的产物净增量；只有在 VERIFY 全量对账时才是权威数字。 */
    int outputDelta() { return countOf(output) - outputBefore; }

    boolean workEmpty(AbstractContainerMenu menu) {
        return menu.getCarried().isEmpty() && menu.getSlot(INPUT_SLOT).getItem().isEmpty()
                && menu.getSlot(RESULT_SLOT).getItem().isEmpty();
    }

    boolean verifiedAfterCrafts(AbstractContainerMenu menu) {
        return workEmpty(menu) && countOf(input) == inputBefore - count && countOf(output) == outputBefore + count;
    }

    private boolean hasOutputSpace() {
        var sample = new ItemStack(BuiltInRegistries.ITEM.get(output));
        int capacity = 0;
        for (int i = 0; i < 36 && capacity < count; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) capacity += sample.getMaxStackSize();
            else if (stack.getItem() == sample.getItem()) capacity += sample.getMaxStackSize() - stack.getCount();
        }
        return capacity >= count;
    }

    private int countOf(ResourceLocation item) {
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(item)) total += stack.getCount();
        }
        return total;
    }

    private boolean isInput(ItemStack stack) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(input);
    }

    private int menuSlot(AbstractContainerMenu menu, int inventorySlot) {
        // 不假定玩家背包恰好从某个菜单编号开始，按真实 Slot 的容器与来源格建立对应关系。
        for (int i = 2; i < menu.slots.size(); i++) {
            var slot = menu.getSlot(i);
            if (slot.container == player.getInventory() && slot.getContainerSlot() == inventorySlot) return i;
        }
        throw new IllegalStateException("stonecutting_inventory_slot_missing");
    }
}
