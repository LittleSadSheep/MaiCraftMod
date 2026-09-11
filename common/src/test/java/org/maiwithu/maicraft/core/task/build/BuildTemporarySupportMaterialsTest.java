// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Exact permanent materials remain reserved even when a spare scaffold is only in cold inventory. */
public final class BuildTemporarySupportMaterialsTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var targets = new ArrayList<BuildTaskRecord.Target>();
        for (int i = 0; i < 36; i++) targets.add(new BuildTaskRecord.Target(Blocks.STONE.defaultBlockState(), Items.STONE,
                new BlockPos(i, 70, 0), "permanent", null, null, null));
        var required = BuildTemporarySupportMaterials.remaining(targets, target -> false);
        check(required.get(Items.STONE) == 36, "Reserve the entire frozen target list");
        var inventory = inventory(36, 64);
        var allowed = List.of(Items.STONE, Items.COBBLESTONE);
        var choice = BuildTemporarySupportMaterials.inventoryChoice(inventory, allowed, required);
        check(choice != null && choice.item() == Items.COBBLESTONE && choice.inventorySlot() == 16,
                "Hotbar stone reserved for construction must not hide cold-inventory cobblestone");
        check(inventory.get(0).getCount() == 36 && inventory.get(16).getCount() == 64,
                "Selection policy must never edit or predict inventory");
        check(BuildTemporarySupportMaterials.inventoryChoice(inventory(36, 0), allowed, required) == null,
                "Exact permanent stock has no spare temporary support material");
        check(!BuildTemporarySupportMaterials.canSpend(36, 36, 1), "The final placement guard must reject reserved stone");
        var surplus = BuildTemporarySupportMaterials.inventoryChoice(inventory(37, 0), allowed, required);
        check(surplus != null && surplus.item() == Items.STONE, "Genuine surplus stone can support construction");
        check(BuildTemporarySupportMaterials.choose(allowed, required, item -> item == Items.STONE ? 37 : 0, 2, false) == null,
                "One surplus item cannot fund a two-block support chain");
        check(BuildTemporarySupportMaterials.choose(allowed, required, item -> item == Items.STONE ? 40 : 64, 1, false) == Items.COBBLESTONE,
                "Non-permanent material wins even when permanent material has a surplus");

        // SemanticBuildSupply hands every child the complete source.targets, not only its affordable prefix.
        var laterBatch = BuildTemporarySupportMaterials.remaining(targets, target -> target.pos().getX() < 18);
        check(laterBatch.get(Items.STONE) == 18, "Only genuinely satisfied permanent targets leave the reservation");
        check(BuildTemporarySupportMaterials.inventoryChoice(inventory(18, 64), allowed, laterBatch).item() == Items.COBBLESTONE,
                "Materials for later batches remain protected while the current batch navigates");
        var lastCell = BuildTemporarySupportMaterials.remaining(targets, target -> target.pos().getX() < 35);
        check(lastCell.get(Items.STONE) == 1 && BuildTemporarySupportMaterials.inventoryChoice(inventory(1, 64), allowed, lastCell).item() == Items.COBBLESTONE,
                "The last required stone cannot become an en-route scaffold");
        check(BuildTemporarySupportMaterials.inventoryChoice(inventory(1, 0), allowed, lastCell) == null,
                "A later batch cannot quietly borrow its last permanent block");
        check(BuildTemporarySupportMaterials.remaining(targets, target -> true).isEmpty(), "Confirmed construction releases its reservation");
        System.out.println("BuildTemporarySupportMaterialsTest: passed");
    }

    private static List<ItemStack> inventory(int stone, int cobble) {
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < 36; i++) result.add(ItemStack.EMPTY);
        if (stone > 0) result.set(0, new ItemStack(Items.STONE, stone));
        if (cobble > 0) result.set(16, new ItemStack(Items.COBBLESTONE, cobble));
        return result;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
