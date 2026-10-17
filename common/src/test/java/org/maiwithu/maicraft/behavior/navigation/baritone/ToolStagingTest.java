// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;

/**
 * 挖路的工具只在主背包时挪到快捷栏：主背包里的那把比快捷栏里的顺手才挪，挪到空格或零碎东西那格；
 * 快捷栏里已经有一样好的就不挪。
 */
class ToolStagingTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static List<ItemStack> backpack() {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 36; i++) items.add(ItemStack.EMPTY);
        return items;
    }

    // 单元测试里方块标签没有绑定，镐挖石头的速度读不出来；剪刀剪蜘蛛网写死在剪刀里，用它来演示。
    @Test
    void theBetterToolInTheMainBackpackComesToTheHotbar() {
        // 快捷栏里是火把和几样零碎；剪刀在主背包：挪到快捷栏的零碎格去。
        List<ItemStack> items = backpack();
        items.set(0, new ItemStack(Items.TORCH, 16));
        items.set(1, new ItemStack(Items.WHEAT_SEEDS, 3));
        for (int slot = 2; slot < 9; slot++) items.set(slot, new ItemStack(Items.TORCH, 1));
        items.set(29, new ItemStack(Items.SHEARS));
        var swap = ToolStaging.plan(items, 0, Blocks.COBWEB.defaultBlockState(), ItemsToKeep.NOTHING);
        assertTrue(swap.isPresent());
        assertEquals(29, swap.get().sourceSlot());
        assertEquals(1, swap.get().hotbarSlot(), "换掉零碎的种子，火把不挪");
    }

    @Test
    void aGoodEnoughToolAlreadyInTheHotbarStays() {
        List<ItemStack> items = backpack();
        items.set(3, new ItemStack(Items.SHEARS));
        items.set(20, new ItemStack(Items.SHEARS));
        assertTrue(ToolStaging.plan(items, 0, Blocks.COBWEB.defaultBlockState(), ItemsToKeep.NOTHING).isEmpty());
    }
}
