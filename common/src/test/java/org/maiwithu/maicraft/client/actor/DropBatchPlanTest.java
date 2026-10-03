// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.inventory.DropBatchPlan;

/** 回放原版菜单真实分堆：空包、满包和装备槽都须精确扣数，而且每个批次只能有一次地面投掷。 */
public final class DropBatchPlanTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            world.enableCraftingTransactions();
            var nativeDrops = world.h.simulateItemDrops();
            for (boolean full : new boolean[]{false, true}) for (int count = 1; count <= 64; count++) {
                world.inventory.clearContent();
                if (full) for (int slot = 0; slot < 36; slot++) world.inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
                world.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
                nativeDrops.clear(); replay(world, 0, count);
                check(nativeDrops.size() == 1 && nativeDrops.getFirst().getCount() == count, "native player receives one complete requested stack");
                check(PlayerInv.count(world.inventory, Items.COBBLESTONE) == 64 - count, "partial stack exact remaining count");
                check(world.player.containerMenu.getCarried().isEmpty(), "partial drop leaves no unaccounted cursor items");
            }
            // 副手及盔甲槽从真实菜单映射，不把 Inventory 的 36..40 错当成快捷栏菜单编号。
            for (int slot : new int[]{36, 37, 38, 39, 40}) {
                world.inventory.clearContent(); world.inventory.setItem(slot, new ItemStack(Items.DIAMOND));
                world.inventory.setItem(0, new ItemStack(Items.EMERALD, 7));
                replay(world, slot, 1);
                check(PlayerInv.count(world.inventory, Items.EMERALD) == 7, "equipment drop preserves unrelated hotbar stack");
            }
            world.inventory.clearContent(); world.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            world.inventory.setItem(8, new ItemStack(Items.COBBLESTONE, 16));
            check(DropBatchPlan.source(world.inventory, Items.COBBLESTONE, 16) == 8, "whole matching stack is preferred over splitting first slot");
        }
        System.out.println("DropBatchPlanTest: all 1..64 quantities, full inventory and equipment passed");
    }

    private static void replay(InteractionWorldTestHarness world, int source, int amount) {
        var menu = world.player.containerMenu; int throwsCount = 0, removed = 0;
        var plan = DropBatchPlan.plan(menu, world.inventory, source, amount);
        for (var click : plan) {
            check(click.matches(menu, false), "native menu starts at the planned source, staging and cursor counts");
            menu.clicked(click.slot(), click.button(), click.type(), world.player);
            check(click.matches(menu, true), "native menu reaches the exact postcondition");
            if (click.dropped() > 0) { throwsCount++; removed += click.dropped(); }
        }
        check(throwsCount == 1 && removed == amount, "partial stack is thrown once at the requested amount");
        // 有空槽时 40 个无需逐个搬运；复用分堆规划应明显少于逐个丢弃的 40 次原生操作。
        if (amount == 40 && plan.getLast().slot() != -999) check(plan.size() < 15, "partial batch uses short native splitting sequence");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
