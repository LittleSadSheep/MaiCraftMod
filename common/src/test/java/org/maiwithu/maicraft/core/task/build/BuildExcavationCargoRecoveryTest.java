// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.settings.ScaffoldMaterials;

/** 续建重新创建任务时也整理旧土石，保留完整建材、配置兼容的支撑和珍藏物品。 */
public final class BuildExcavationCargoRecoveryTest {
    private static final ResourceLocation DIRT = id("dirt"), COBBLE = id("cobblestone");
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        List<String> previous = ScaffoldMaterials.storedIds(null);
        try {
            ScaffoldMaterials.store(null, ScaffoldMaterials.factoryDefaultIds());
            resumedSoilDoesNotBecomePermanentStartingStock();
            customizedTypesAndValuablesRemainUntouched();
            supportReservationsFollowConfigurationAndExplicitPlans();
        } finally { ScaffoldMaterials.store(null, previous); }
        System.out.println("BuildExcavationCargoRecoveryTest: passed");
    }

    private static void resumedSoilDoesNotBecomePermanentStartingStock() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            // 模拟多次续建后的背包：八组旧泥土、两组圆石、一千一百二十一块石砖和工具食物。
            for (int slot = 0; slot < 8; slot++) h.inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
            for (int slot = 8; slot < 10; slot++) h.inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            for (int slot = 10; slot < 28; slot++) h.inventory.setItem(slot, new ItemStack(Items.STONE_BRICKS, slot == 27 ? 33 : 64));
            h.inventory.setItem(28, new ItemStack(Items.DIAMOND_PICKAXE));
            h.inventory.setItem(29, new ItemStack(Items.BREAD, 32));
            h.inventory.setItem(30, new ItemStack(Items.OAK_LOG, 16));
            var restored = new BuildExcavationCargo(); restored.begin(h.player);
            var needs = Map.of(Items.STONE_BRICKS, 1121);
            var expected = Map.of(DIRT, 448, COBBLE, 128);
            check(restored.unloadable(h.player, needs).equals(expected), "新任务仍可存掉旧泥土圆石，只留下全局一组泥土支撑");
            check(BuildExcavationCargo.surplus(h.player, needs).equals(expected), "出坑供料与开挖阶段采用相同余料规则");
            check(h.inventory.getItem(27).getCount() == 33 && h.inventory.getItem(28).is(Items.DIAMOND_PICKAXE)
                    && h.inventory.getItem(29).getCount() == 32 && h.inventory.getItem(30).getCount() == 16,
                    "整理估算不动石砖、工具、食物、木头或任何真实库存");
        }
    }

    private static void customizedTypesAndValuablesRemainUntouched() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.DIRT, 64)); h.inventory.setItem(1, new ItemStack(Items.DIRT, 64));
            h.inventory.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("纪念土块"));
            h.inventory.setItem(2, new ItemStack(Items.COBBLESTONE, 64)); h.inventory.setItem(3, new ItemStack(Items.COBBLESTONE, 64));
            h.inventory.setItem(4, new ItemStack(Items.DIAMOND_BLOCK, 64));
            var cargo = new BuildExcavationCargo(); cargo.begin(h.player); cargo.observedTerrain(Blocks.DIAMOND_BLOCK.defaultBlockState());
            check(cargo.unloadable(h.player, Map.of()).equals(Map.of(COBBLE, 64)), "命名土块所在整类保留，珍贵整块不能因可堆叠就变成土石");
            check(h.blockUses() == 0 && h.itemUses() == 0, "分类不替代实际开箱和搬运");
        }
    }

    private static void supportReservationsFollowConfigurationAndExplicitPlans() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            for (int slot = 0; slot < 8; slot++) h.inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            h.inventory.setItem(8, new ItemStack(Items.DIRT, 64)); h.inventory.setItem(9, new ItemStack(Items.DIRT, 64));
            ScaffoldMaterials.store(h.player, List.of("minecraft:cobblestone"));
            check(BuildExcavationCargo.surplus(h.player, Map.of(Items.COBBLESTONE, 100)).equals(Map.of(COBBLE, 348, DIRT, 128)),
                    "保留一百份永久材料后，只额外留一组配置允许的圆石支撑");
            check(BuildExcavationCargo.surplus(h.player, Map.of(Items.COBBLESTONE, 100), Map.of(Items.COBBLESTONE, 160))
                    .equals(Map.of(COBBLE, 252, DIRT, 128)), "明确计划需要一百六十份支撑时，不得被默认一组截断");
            ScaffoldMaterials.store(h.player, List.of());
            check(BuildExcavationCargo.surplus(h.player, Map.of(Items.COBBLESTONE, 100)).equals(Map.of(COBBLE, 412, DIRT, 128)),
                    "玩家禁止支撑材料时不擅自退回默认泥土储备");
        }
    }
    private static ResourceLocation id(String value) { return ResourceLocation.withDefaultNamespace(value); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
