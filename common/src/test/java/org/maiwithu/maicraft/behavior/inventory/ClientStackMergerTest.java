// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 合并散堆的配对：同一种物品、并得下的才成对，数量小的并进余量大的。 */
class ClientStackMergerTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ClientStackMerger.SlotStack slot(int slot, ItemStack stack) {
        return new ClientStackMerger.SlotStack(slot, stack);
    }

    @Test
    void 同一种物品并得下的成对() {
        Optional<ClientStackMerger.MergePair> pair = ClientStackMerger.findMergeablePair(List.of(
                slot(9, new ItemStack(Items.COBBLESTONE, 32)),
                slot(10, new ItemStack(Items.COBBLESTONE, 32))));
        assertTrue(pair.isPresent());
        // 数量相同取格号小的作源、大的作目标，顺序稳定可复现。
        assertEquals(9, pair.get().sourceSlot());
        assertEquals(10, pair.get().targetSlot());
        assertEquals(32, pair.get().stack().getCount());
    }

    @Test
    void 三堆都装不进彼此就配不成对() {
        // 40+40 超过一格上限，纯合并腾不出格子：不配对，计划里也就不该指望合并。
        Optional<ClientStackMerger.MergePair> pair = ClientStackMerger.findMergeablePair(List.of(
                slot(9, new ItemStack(Items.COBBLESTONE, 40)),
                slot(10, new ItemStack(Items.COBBLESTONE, 40)),
                slot(11, new ItemStack(Items.COBBLESTONE, 40))));
        assertTrue(pair.isEmpty());
    }

    @Test
    void 零头并进大堆() {
        Optional<ClientStackMerger.MergePair> pair = ClientStackMerger.findMergeablePair(List.of(
                slot(12, new ItemStack(Items.COBBLESTONE, 1)),
                slot(9, new ItemStack(Items.COBBLESTONE, 63))));
        assertTrue(pair.isPresent());
        assertEquals(12, pair.get().sourceSlot());
        assertEquals(9, pair.get().targetSlot());
    }

    @Test
    void 不同物品不成对() {
        Optional<ClientStackMerger.MergePair> pair = ClientStackMerger.findMergeablePair(List.of(
                slot(9, new ItemStack(Items.COBBLESTONE, 32)),
                slot(10, new ItemStack(Items.DIRT, 32))));
        assertTrue(pair.isEmpty());
    }

    @Test
    void 数量小的先找余量最大的去处() {
        // 63、1、1：把一个零头并进另一个零头，63 那格留着继续收，比并进 63 更合理。
        Optional<ClientStackMerger.MergePair> pair = ClientStackMerger.findMergeablePair(List.of(
                slot(9, new ItemStack(Items.COBBLESTONE, 63)),
                slot(10, new ItemStack(Items.COBBLESTONE, 1)),
                slot(11, new ItemStack(Items.COBBLESTONE, 1))));
        assertTrue(pair.isPresent());
        // 最小堆是 10 格那个，余量最大的是 11 格那个：10 并进 11。
        assertEquals(10, pair.get().sourceSlot());
        assertEquals(11, pair.get().targetSlot());
    }
}
