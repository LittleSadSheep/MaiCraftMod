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

/** 即使多余脚手架只在尚未选中的背包槽位中，永久材料仍须精确保留。 */
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

        // SemanticBuildSupply 会将完整 source.targets 交给每个子任务，而不只是当前负担得起的前缀。
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
        // 已拿到光滑石仍不能充当默认垫块；需求只能从白名单选取，并优先补齐已有的同一种支撑材料。
        var need = BuildTemporarySupportMaterials.supplyNeed(List.of(Items.DIRT, Items.COBBLESTONE), Map.of(),
                item -> item == Items.COBBLESTONE ? 2 : 0, 3);
        check(need.item() == Items.COBBLESTONE && need.requiredFinalCount() == 3, "补齐已有圆石到三块而不猜光滑石");
        var reservedNeed = BuildTemporarySupportMaterials.supplyNeed(List.of(Items.STONE), Map.of(Items.STONE, 36), item -> 36, 3);
        check(reservedNeed.requiredFinalCount() == 39, "保留三十六块永久石头后再补三块临时支撑");
        check(BuildTemporarySupportMaterials.supplyNeed(List.of(Items.SAND), Map.of(), item -> 0, 3) == null,
                "不能给会掉落的方块发出支撑供料需求");
        // 泥土没库存时仍保留圆石候选；硬质或机器方块即使配置允许，也不能用于待回收的临时支撑。
        var options = BuildTemporarySupportMaterials.supplyOptions(List.of(Items.DIRT, Items.COBBLESTONE,
                Items.OBSIDIAN, Items.CHEST, Items.BEDROCK), Map.of(), item -> 0, 2);
        check(options.stream().map(BuildTemporarySupportMaterials.SupplyNeed::item).toList().equals(List.of(Items.DIRT, Items.COBBLESTONE)),
                "全部易拆候选必须保留，不能因为泥土排在前面就直接外出挖土");
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
