package org.maiwithu.maicraft.core.task.mine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Tests the production selector used by prepared work batches, including worn/staged tools. */
public final class MiningToolRequirementTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.BLOCK.bindTags(Map.of(
                BlockTags.MINEABLE_WITH_AXE, List.of(Blocks.BIRCH_LOG.builtInRegistryHolder()),
                BlockTags.MINEABLE_WITH_PICKAXE, List.of(Blocks.STONE.builtInRegistryHolder(),
                        Blocks.OBSIDIAN.builtInRegistryHolder()),
                BlockTags.NEEDS_DIAMOND_TOOL, List.of(Blocks.OBSIDIAN.builtInRegistryHolder()),
                BlockTags.INCORRECT_FOR_STONE_TOOL, List.of(Blocks.OBSIDIAN.builtInRegistryHolder())));
        var logs = Set.of(Blocks.BIRCH_LOG);
        List<ItemStack> inventory = new ArrayList<>(Collections.nCopies(37, ItemStack.EMPTY));
        check(!MineBlockTaskRecord.hasEfficientTool(inventory, logs), "bare hands counted as an efficient axe");
        inventory.set(0, new ItemStack(Items.STONE_PICKAXE));
        check(!MineBlockTaskRecord.hasEfficientTool(inventory, logs), "wrong tool family prevented axe replenishment");
        ItemStack axe = new ItemStack(Items.STONE_AXE);
        inventory.set(35, axe);
        check(MineBlockTaskRecord.hasEfficientTool(inventory, logs), "a main-backpack tool was ignored until held");
        axe.setDamageValue(axe.getMaxDamage() - 1);
        check(MineBlockTaskRecord.hasEfficientTool(inventory, logs), "a tool with one real use left was treated as broken");
        axe.setDamageValue(axe.getMaxDamage());
        check(!MineBlockTaskRecord.hasEfficientTool(inventory, logs), "a depleted stack authorized bare-hand fallback");
        inventory.set(36, new ItemStack(Items.IRON_AXE));
        check(!MineBlockTaskRecord.hasEfficientTool(inventory, logs), "a slot outside the main inventory counted as staged work gear");
        inventory.set(17, new ItemStack(Items.IRON_AXE));
        check(MineBlockTaskRecord.hasEfficientTool(inventory, logs), "another valid carried axe was not reusable");
        check(!MineBlockTaskRecord.hasEfficientTool(List.of(new ItemStack(Items.STONE_PICKAXE)),
                Set.of(Blocks.OBSIDIAN)), "speed without legal drops counted as a harvesting tool");
        check(MineBlockTaskRecord.hasEfficientTool(List.of(new ItemStack(Items.DIAMOND_PICKAXE)),
                Set.of(Blocks.OBSIDIAN)), "correct-tier harvesting tool was rejected");
        check(!new MineBlockTaskRecord("bootstrap", 100, logs, 3, "logs").requireEfficientTool,
                "bootstrap mining unexpectedly requires the tool it is collecting ingredients to craft");
        check(new MineBlockTaskRecord("prepared", 100, logs, 24, "logs", Set.of(Items.BIRCH_LOG), true)
                        .requireEfficientTool,
                "prepared work did not retain its no-bare-hand-fallback contract");
        System.out.println("MiningToolRequirementTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
