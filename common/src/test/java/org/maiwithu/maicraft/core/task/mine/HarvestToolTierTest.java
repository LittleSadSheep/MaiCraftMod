// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.mine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.act.ToolSelect;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;
import org.maiwithu.maicraft.core.task.acquire.SemanticSourceKnowledge;
import org.maiwithu.maicraft.task.TaskState;

/** 石料、铁矿和钻石矿保留真实掉落等级；效率不足不得单独阻断可徒手采集的方块。 */
public final class HarvestToolTierTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var iron = Blocks.IRON_ORE.builtInRegistryHolder();
        var diamond = Blocks.DIAMOND_ORE.builtInRegistryHolder();
        BuiltInRegistries.BLOCK.bindTags(Map.of(
                BlockTags.MINEABLE_WITH_PICKAXE, List.of(Blocks.STONE.builtInRegistryHolder(), iron, diamond),
                BlockTags.NEEDS_STONE_TOOL, List.of(iron), BlockTags.NEEDS_IRON_TOOL, List.of(diamond),
                BlockTags.INCORRECT_FOR_WOODEN_TOOL, List.of(iron, diamond),
                BlockTags.INCORRECT_FOR_GOLD_TOOL, List.of(iron, diamond),
                BlockTags.INCORRECT_FOR_STONE_TOOL, List.of(diamond)));
        try (var world = new InteractionWorldTestHarness()) {
            required(world, Blocks.STONE, null, Items.WOODEN_PICKAXE);
            required(world, Blocks.IRON_ORE, Items.WOODEN_PICKAXE, Items.STONE_PICKAXE);
            required(world, Blocks.DIAMOND_ORE, Items.STONE_PICKAXE, Items.IRON_PICKAXE);
            // 金镐虽快但不能掉钻石；真正执行时应选择主背包里的铁镐。
            world.inventory.clearContent();
            world.inventory.setItem(0, new ItemStack(Items.GOLDEN_PICKAXE));
            // 只有副手有铁镐时，当前主手仍不能采出钻石；前置判定不能比实际工具切换器多算槽位。
            world.inventory.setItem(40, new ItemStack(Items.IRON_PICKAXE));
            check(!BlockHelper.canHarvest(world.inventory, Blocks.DIAMOND_ORE.defaultBlockState()), "副手镐不能担保主手采矿掉落");
            world.inventory.setItem(40, ItemStack.EMPTY);
            world.inventory.setItem(20, new ItemStack(Items.IRON_PICKAXE));
            check(ToolSelect.bestSlot(world.player, Blocks.DIAMOND_ORE.defaultBlockState()) == 20,
                    "采收正确性必须优先于挖掘速度");
            check(SemanticSourceKnowledge.missingTool(world.player, Set.of(Blocks.DIAMOND_ORE)) == null,
                    "已有铁镐可正常取得钻石");
            // 工具已用尽但泥土仍能徒手掉落：实际执行一刻，不能因历史效率提示立即返回 WRONG_TOOL。
            world.inventory.clearContent(); world.set(new BlockPos(2, 1, 2), Blocks.DIRT.defaultBlockState());
            var effects = LivingEntity.class.getDeclaredField("activeEffects"); effects.setAccessible(true);
            effects.set(world.player, new HashMap<>());
            var record = new MineBlockTaskRecord("slow-harvest", 1000, Set.of(Blocks.DIRT), 4, "dirt", Set.of(Items.DIRT), true)
                    .withinRadius(world.player.blockPosition(), 4);
            var task = new MineCompanionTask(world.player, record); task.start(world.player);
            check(task.tick(world.player) == TaskState.RUNNING, "缺快速工具时仍应继续可执行的采集");
        }
        System.out.println("HarvestToolTierTest: passed");
    }

    private static void required(InteractionWorldTestHarness world, Block source, Item carried, Item expected) {
        // 错等级工具必须被采矿过滤；规划返回的最低成本工具应能通过相同的原生掉落判定。
        world.inventory.clearContent();
        if (carried != null) world.inventory.setItem(0, new ItemStack(carried));
        check(!BlockHelper.canHarvest(world.inventory, source.defaultBlockState()), "错误工具不能假装有掉落");
        var tool = SemanticSourceKnowledge.missingTool(world.player, Set.of(source));
        check(tool != null && tool.acceptableItemIds().contains(BuiltInRegistries.ITEM.getKey(expected)), "保留真实最低镐级别");
        world.inventory.setItem(1, new ItemStack(expected));
        check(BlockHelper.canHarvest(world.inventory, source.defaultBlockState()), "正确工具应允许采收");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
