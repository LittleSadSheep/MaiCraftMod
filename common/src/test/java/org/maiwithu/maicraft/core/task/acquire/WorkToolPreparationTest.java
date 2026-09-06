package org.maiwithu.maicraft.core.task.acquire;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class WorkToolPreparationTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        BuiltInRegistries.BLOCK.bindTags(Map.of(
                BlockTags.LOGS, List.of(Blocks.BIRCH_LOG.builtInRegistryHolder()),
                BlockTags.MINEABLE_WITH_AXE, List.of(Blocks.BIRCH_LOG.builtInRegistryHolder()),
                BlockTags.MINEABLE_WITH_PICKAXE, List.of(Blocks.STONE.builtInRegistryHolder(),
                        Blocks.OBSIDIAN.builtInRegistryHolder()),
                BlockTags.MINEABLE_WITH_SHOVEL, List.of(Blocks.DIRT.builtInRegistryHolder()),
                BlockTags.MINEABLE_WITH_HOE, List.of(Blocks.HAY_BLOCK.builtInRegistryHolder()),
                BlockTags.NEEDS_DIAMOND_TOOL, List.of(Blocks.OBSIDIAN.builtInRegistryHolder()),
                BlockTags.INCORRECT_FOR_STONE_TOOL, List.of(Blocks.OBSIDIAN.builtInRegistryHolder()),
                BlockTags.INCORRECT_FOR_IRON_TOOL, List.of(Blocks.OBSIDIAN.builtInRegistryHolder())));
        BuiltInRegistries.ITEM.bindTags(Map.of(
                ItemTags.LOGS, List.of(Items.BIRCH_LOG.builtInRegistryHolder()),
                ItemTags.PLANKS, List.of(Items.BIRCH_PLANKS.builtInRegistryHolder()),
                ItemTags.STONE_TOOL_MATERIALS, List.of(Items.COBBLESTONE.builtInRegistryHolder())));
        BlockState wood = Blocks.BIRCH_LOG.defaultBlockState();
        check(WorkToolPreparation.bootstrapLimit(List.of(), List.of(wood)) == 3,
                "a fresh body must gather bootstrap wood before requesting a stone axe");
        check(WorkToolPreparation.bootstrapLimit(List.of(new ItemStack(Items.WOODEN_PICKAXE),
                new ItemStack(Items.CRAFTING_TABLE), new ItemStack(Items.STICK, 2)), List.of(wood)) == 0,
                "an established body must not gather unnecessary bootstrap wood");
        check(WorkToolPreparation.bootstrapLimit(List.of(new ItemStack(Items.WOODEN_PICKAXE)),
                List.of(Blocks.STONE.defaultBlockState())) == 3,
                "collect the first stone-tool materials with the existing wooden pick to avoid a recipe cycle");
        expect(wood, 24, 0, 0, 3, "stone_axe", false);
        expect(wood, 4, 0, 0, 3, "stone_axe", false);
        check(choose(List.of(), wood, 3, 0, 0, 3) == null,
                "bootstrap logs/cobblestone must not recursively demand another efficiency tool");
        expect(wood, 24, 63, 191, 3, "stone_axe", false);
        expect(wood, 24, 64, 191, 3, "iron_axe", true);
        expect(wood, 24, 64, 192, 3, "diamond_axe", true);
        expect(wood, 24, 64, 192, 1, "stone_axe", false);
        expect(Blocks.STONE.defaultBlockState(), 24, 0, 0, 3, "stone_pickaxe", false);
        expect(Blocks.DIRT.defaultBlockState(), 24, 0, 0, 3, "stone_shovel", false);
        expect(Blocks.HAY_BLOCK.defaultBlockState(), 24, 0, 0, 3, "stone_hoe", false);
        expect(Blocks.OBSIDIAN.defaultBlockState(), 24, 0, 0, 1, "diamond_pickaxe", false);
        check(choose(List.of(new ItemStack(Items.IRON_AXE)), wood, 24, 0, 0, 3) == null,
                "reuse an adequate carried tool without buying a redundant lower-tier one");
        check(choose(List.of(new ItemStack(Items.STONE_PICKAXE)), wood, 24, 0, 0, 3) != null,
                "a wrong tool family cannot stand in for an axe");
        ItemStack worn = new ItemStack(Items.IRON_AXE);
        worn.setDamageValue(worn.getMaxDamage() - 3);
        check(choose(List.of(worn), wood, 24, 0, 0, 3) != null,
                "a tool about to break cannot satisfy preparation for a large batch");
        ItemStack partlyUsed = new ItemStack(Items.STONE_AXE);
        partlyUsed.setDamageValue(partlyUsed.getMaxDamage() - 40);
        check(WorkToolPreparation.batchLimit(List.of(partlyUsed), List.of(wood), 128) == 32,
                "a work child must return for tool preparation before its remaining durability runs out");
        check(WorkToolPreparation.batchLimit(List.of(new ItemStack(Items.STONE_AXE)), List.of(wood), 24) == 24,
                "a complete ordinary tree batch must not be split unnecessarily");
        check(choose(List.of(new ItemStack(Items.DIAMOND_AXE)), wood, 24, 0, 0, 3) == null,
                "abundance thresholds govern new spending, not reuse of owned diamond tools");
        check(WorkToolPreparation.tillingTool(List.of(new ItemStack(Items.STONE_SHOVEL)), 0, 0)
                        .itemId().getPath().equals("stone_hoe"), "tilling needs a hoe, not the fastest dirt digger");
        check(WorkToolPreparation.tillingTool(List.of(), 64, 192).itemId().getPath().equals("diamond_hoe"),
                "tilling must share the owner's explicit abundance thresholds");
        check(WorkToolPreparation.tillingTool(List.of(new ItemStack(Items.IRON_HOE)), 0, 0).carried(),
                "reuse a suitable hoe already carried");
        System.out.println("WorkToolPreparationTest: passed");
    }

    private static WorkToolPreparation.Choice choose(List<ItemStack> inventory, BlockState state,
            int work, long iron, long diamonds, int cap) {
        return WorkToolPreparation.missing(inventory, List.of(state), work, iron, diamonds, cap);
    }

    private static void expect(BlockState state, int work, long iron, long diamonds, int cap,
            String item, boolean stockOnly) {
        var choice = choose(List.of(), state, work, iron, diamonds, cap);
        check(choice != null && choice.requirement().acceptableItemIds().getFirst().getPath().equals(item)
                        && choice.stockOnly() == stockOnly,
                "unexpected preparation for " + state + ": " + choice);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
