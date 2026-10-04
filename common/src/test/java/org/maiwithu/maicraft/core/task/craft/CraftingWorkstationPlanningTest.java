// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.craft;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** 背包有工作台时准备原生放置，制箭台与锻造台不能冒充普通三乘三合成台。 */
public final class CraftingWorkstationPlanningTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        carriedTable();
        unrelatedWorkstations();
        carriedTableWithoutSupport();
        System.out.println("CraftingWorkstationPlanningTest: passed");
    }

    private static void carriedTable() throws Exception {
        // 快捷栏、主背包末格都属于可取出的现货；没有世界工作台也应派放置，不能展开木料前置。
        for (int slot : List.of(0, 12, 35)) try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(8.5, 1, 8.5));
            h.inventory.setItem(slot, new ItemStack(Items.CRAFTING_TABLE));
            var snapshot = CraftingWorkstationCoordinator.inspect(h.player);
            check(snapshot.surface() == CraftPlanCost.Surface.PREPARABLE
                    && snapshot.prerequisiteItemIds().isEmpty(), "已有工作台只需摆放，不应再造一张");
            var coordinator = new CraftingWorkstationCoordinator();
            try {
                var next = coordinator.next(h.player, null);
                check(next.action() == CraftingWorkstationCoordinator.Action.PLACE_CARRIED
                        && next.workstationBlock() == Blocks.CRAFTING_TABLE, "执行入口必须使用已有工作台");
                check(h.blockUses() == 0 && h.inventory.countItem(Items.CRAFTING_TABLE) == 1,
                        "准备方案不能提前放台或扣库存");
            } finally { coordinator.close(); }
        }
    }

    private static void unrelatedWorkstations() throws Exception {
        // 原版这两种桌子虽继承工作台类，却不提供普通合成；现货、世界扫描和补台候选须统一排除。
        var candidates = CraftingWorkstationCoordinator.prerequisiteItemIds();
        check(candidates.contains(BuiltInRegistries.ITEM.getKey(Items.CRAFTING_TABLE)), "保留真正的工作台");
        for (var block : List.of(Blocks.FLETCHING_TABLE, Blocks.SMITHING_TABLE)) {
            check(!candidates.contains(BuiltInRegistries.ITEM.getKey(block.asItem())), "不能为普通合成制造制箭台或锻造台");
            try (var h = new InteractionWorldTestHarness()) {
                h.position(new Vec3(8.5, 1, 8.5));
                var at = new BlockPos(7, 1, 8); h.set(at, block.defaultBlockState());
                h.inventory.setItem(0, new ItemStack(block.asItem()));
                check(!CraftingWorkstationCoordinator.usableTable(h.player, at), "世界内的其他桌子不能冒充合成台");
                var snapshot = CraftingWorkstationCoordinator.inspect(h.player);
                check(snapshot.surface() == CraftPlanCost.Surface.PREREQUISITE, "携带错误桌子仍需真正工作台");
            }
        }
    }

    private static void carriedTableWithoutSupport() throws Exception {
        // 玩家周围没有承重面时如实报告摆放条件，仍保留背包已有台的事实，不能让调用者误以为要再找原木。
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(8.5, 1, 8.5));
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                h.set(new BlockPos(x, 0, z), Blocks.AIR.defaultBlockState());
            h.inventory.setItem(12, new ItemStack(Items.CRAFTING_TABLE));
            var snapshot = CraftingWorkstationCoordinator.inspect(h.player);
            check(snapshot.surface() == CraftPlanCost.Surface.UNAVAILABLE && snapshot.prerequisiteItemIds().isEmpty(),
                    "没处摆台不需要追加工作台材料");
            var facts = snapshot.facts(h.player);
            check(facts.get("main_inventory_workstations").equals(Map.of("minecraft:crafting_table", 1))
                    && facts.get("detail").toString().contains("placement"), "回执说明已有工作台与实际摆放障碍");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
