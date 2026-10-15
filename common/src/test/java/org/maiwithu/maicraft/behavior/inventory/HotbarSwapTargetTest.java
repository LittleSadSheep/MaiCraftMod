// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.HotbarSwapTarget.Holding;

/** 换进快捷栏的哪一格：空格先于零碎东西，工具、吃的、水桶、火把不挪走；同类里不动手里拿着的、靠右的先用。 */
class HotbarSwapTargetTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void emptySlotsComeBeforeOddsAndEndsAndTheHeldSlotComesLastWithinEach() {
        List<Holding> hotbar = new ArrayList<>(List.of(Holding.KEEP, Holding.MOVABLE, Holding.EMPTY, Holding.KEEP,
                Holding.KEEP, Holding.KEEP, Holding.MOVABLE, Holding.KEEP, Holding.KEEP));
        assertEquals(2, HotbarSwapTarget.pick(hotbar, 0));
        // 唯一的空格正拿在手上（空手）：照样用它，空格换进来什么都不挤走。
        assertEquals(2, HotbarSwapTarget.pick(hotbar, 2));
        hotbar.set(2, Holding.KEEP);
        hotbar.set(1, Holding.KEEP);
        // 只剩手上那一格能腾：退而用它。
        assertEquals(6, HotbarSwapTarget.pick(hotbar, 6));
        hotbar.set(6, Holding.KEEP);
        assertEquals(-1, HotbarSwapTarget.pick(hotbar, 0));
    }

    @Test
    void toolsFoodBucketsAndTorchesStayAndOddsAndEndsMayGo() {
        assertEquals(Holding.KEEP, HotbarSwapTarget.holding(new ItemStack(Items.STONE_PICKAXE), ItemsToKeep.NOTHING));
        assertEquals(Holding.KEEP, HotbarSwapTarget.holding(new ItemStack(Items.BREAD), ItemsToKeep.NOTHING));
        assertEquals(Holding.KEEP, HotbarSwapTarget.holding(new ItemStack(Items.WATER_BUCKET), ItemsToKeep.NOTHING));
        assertEquals(Holding.KEEP, HotbarSwapTarget.holding(new ItemStack(Items.TORCH), ItemsToKeep.NOTHING));
        assertEquals(Holding.MOVABLE, HotbarSwapTarget.holding(new ItemStack(Items.DIRT, 12), ItemsToKeep.NOTHING));
        assertEquals(Holding.EMPTY, HotbarSwapTarget.holding(ItemStack.EMPTY, ItemsToKeep.NOTHING));
        // 正要用的零碎也不挪：木棍是这次要拿的。
        assertEquals(Holding.KEEP, HotbarSwapTarget.holding(new ItemStack(Items.STICK, 3),
                id -> id.equals("minecraft:stick") ? 2 : 0));
    }
}
