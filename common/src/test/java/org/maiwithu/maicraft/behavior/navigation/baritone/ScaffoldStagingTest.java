// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.behavior.navigation.baritone.ScaffoldStaging.Holding;

/**
 * 垫料只在主背包时挪到快捷栏的打算：快捷栏已有能垫的就不挪；挪到空格先于零碎东西，
 * 工具、吃的、水桶、火把不挪走；同类里不动手里拿着的、靠右的先用；全是不能动的就不挪。
 */
class ScaffoldStagingTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static List<ItemStack> emptyBackpack() {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 36; i++) items.add(ItemStack.EMPTY);
        return items;
    }

    private static Map<String, Integer> spare(String... ids) {
        Map<String, Integer> spare = new LinkedHashMap<>();
        for (String id : ids) spare.put(id, 16);
        return spare;
    }

    @Test
    void emptySlotsComeBeforeOddsAndEndsAndTheHeldSlotComesLastWithinEach() {
        List<Holding> hotbar = new ArrayList<>(List.of(Holding.KEEP, Holding.MOVABLE, Holding.EMPTY, Holding.KEEP,
                Holding.KEEP, Holding.KEEP, Holding.MOVABLE, Holding.KEEP, Holding.KEEP));
        assertEquals(2, ScaffoldStaging.hotbarTarget(hotbar, 0));
        // 唯一的空格正拿在手上（空手）：照样用它，空格换进来什么都不挤走。
        assertEquals(2, ScaffoldStaging.hotbarTarget(hotbar, 2));
        hotbar.set(2, Holding.KEEP);
        hotbar.set(1, Holding.KEEP);
        // 只剩手上那一格能腾：退而用它。
        assertEquals(6, ScaffoldStaging.hotbarTarget(hotbar, 6));
        hotbar.set(6, Holding.KEEP);
        assertEquals(-1, ScaffoldStaging.hotbarTarget(hotbar, 0));
    }

    @Test
    void backpackScaffoldMovesToAFreeHotbarSlotByScaffoldOrder() {
        List<ItemStack> items = emptyBackpack();
        for (int slot = 0; slot < 9; slot++) items.set(slot, new ItemStack(Items.IRON_PICKAXE));
        items.set(4, new ItemStack(Items.STICK, 3));
        items.set(20, new ItemStack(Items.COBBLESTONE, 32));
        items.set(30, new ItemStack(Items.DIRT, 10));
        var swap = ScaffoldStaging.plan(items, ItemStack.EMPTY, 0,
                spare("minecraft:dirt", "minecraft:cobblestone"), ItemsToKeep.NOTHING);
        // 泥土先垫：主背包第 30 格的泥土换到放木棍的第 4 格，镐子一把不动。
        assertEquals(new ScaffoldStaging.Swap(30, 4, "minecraft:dirt"), swap.orElseThrow());
    }

    @Test
    void nothingMovesWhenTheHotbarAlreadyHoldsScaffoldOrHasNoRoom() {
        List<ItemStack> items = emptyBackpack();
        items.set(20, new ItemStack(Items.DIRT, 10));
        items.set(3, new ItemStack(Items.COBBLESTONE, 5));
        assertTrue(ScaffoldStaging.plan(items, ItemStack.EMPTY, 0,
                spare("minecraft:dirt", "minecraft:cobblestone"), ItemsToKeep.NOTHING).isEmpty());
        // 快捷栏全是工具、吃的、水桶、火把：腾不出格子，不挪。
        items.set(3, ItemStack.EMPTY);
        List<ItemStack> keep = List.of(new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.IRON_SWORD),
                new ItemStack(Items.BREAD), new ItemStack(Items.WATER_BUCKET), new ItemStack(Items.TORCH),
                new ItemStack(Items.IRON_AXE), new ItemStack(Items.IRON_SHOVEL), new ItemStack(Items.COOKED_BEEF),
                new ItemStack(Items.SHIELD));
        for (int slot = 0; slot < 9; slot++) items.set(slot, keep.get(slot));
        assertTrue(ScaffoldStaging.plan(items, ItemStack.EMPTY, 0,
                spare("minecraft:dirt"), ItemsToKeep.NOTHING).isEmpty());
        // 正要用的东西也不挪走：木棍是这次要拿的。
        items.set(8, new ItemStack(Items.STICK, 3));
        assertTrue(ScaffoldStaging.plan(items, ItemStack.EMPTY, 0, spare("minecraft:dirt"),
                id -> id.equals("minecraft:stick") ? 2 : 0).isEmpty());
    }
}
