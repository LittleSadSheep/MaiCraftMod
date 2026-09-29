// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.backpack;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;

/** 随身库存提示绑定身体、世界和物品总量；同一内容UUID的两个物理背包不能重复记库存。 */
public final class BackpackStockTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var item = ResourceLocation.parse("minecraft:redstone");
        var cache = new BackpackStock.Cache(); Object player = new Object(), world = new Object();
        var inventory = Map.of(item, 3L); String id = "sophisticated_backpack:fixture";
        var view = new BackpackMenuAccess.Snapshot(null, id, ItemStack.EMPTY, List.of(), Map.of(), Map.of(item, 512L), Map.of(item, 512L), Set.of(), 20);
        cache.record(player, world, inventory, view); cache.record(player, world, inventory, view);
        check(cache.latest(player, world, inventory, List.of(id, id), 21).size() == 1, "shared logical storage is counted once");
        check(cache.latest(player, world, inventory, List.of(id), 21).getFirst().stored().get(item) == 512L, "carried three items do not inflate backpack stock");
        check(cache.latest(player, world, Map.of(item, 4L), List.of(id), 22).isEmpty(), "new carried quantity invalidates stock instead of guessing its source");
        cache.record(player, world, inventory, view);
        check(cache.latest(player, world, inventory, List.of(), 21).isEmpty(), "a backpack no longer carried provides no supply hint");
        cache.record(player, world, inventory, view);
        check(cache.latest(player, world, inventory, List.of(id), 1221).isEmpty(), "old observation expires");
        cache.record(player, world, inventory, view);
        check(cache.latest(player, new Object(), inventory, List.of(id), 21).isEmpty(), "another world cannot inherit backpack evidence");
        cache.record(player, world, inventory, view);
        check(cache.latest(new Object(), world, inventory, List.of(id), 21).isEmpty(), "another body cannot inherit backpack evidence");
        cache.record(player, world, inventory, view);
        check(cache.latest(player, world, inventory, List.of(id), 19).isEmpty(), "clock rollback cannot reuse a future observation");
        var transientView = new BackpackMenuAccess.Snapshot(null, "backpack_menu:8:fixture", ItemStack.EMPTY, List.of(), Map.of(), Map.of(item, 4L), Map.of(), Set.of(), 20);
        cache.record(player, world, inventory, transientView);
        check(cache.latest(player, world, inventory, List.of(transientView.storageId()), 21).isEmpty(), "unidentified empty or linked menus cannot enter persistent stock totals");
        System.out.println("BackpackStockTest: passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
