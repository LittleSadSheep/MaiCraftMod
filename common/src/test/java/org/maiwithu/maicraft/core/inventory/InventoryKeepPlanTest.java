// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 整理只选不再需要的普通物资；工作材料、口粮、工具和应急块不会因背包满被一起存走。 */
public final class InventoryKeepPlanTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var inventory = new ArrayList<>(List.of(new ItemStack(Items.DIAMOND_PICKAXE), new ItemStack(Items.BUCKET, 3),
                new ItemStack(Items.TORCH, 64), new ItemStack(Items.TORCH), new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.COBBLESTONE, 20), new ItemStack(Items.DIRT, 7), new ItemStack(Items.BONE, 2),
                new ItemStack(Items.ARROW, 2), new ItemStack(Items.BREAD, 12), new ItemStack(Items.GOLD_INGOT, 40)));
        var plan = InventoryKeepPlan.inspect(inventory, Set.of(Items.GOLD_INGOT), List.of(Items.DIRT, Items.COBBLESTONE));
        check(amount(plan, Items.BONE) == 2 && amount(plan, Items.ARROW) == 2 && amount(plan, Items.DIRT) == 7,
                "old miscellaneous items can be stored while the common support material is retained");
        check(amount(plan, Items.COBBLESTONE) == 20 && amount(plan, Items.TORCH) == 1 && amount(plan, Items.BUCKET) == 2,
                "only surplus beyond the common supply limit is stored");
        check(amount(plan, Items.DIAMOND_PICKAXE) == 0 && amount(plan, Items.BREAD) == 0 && amount(plan, Items.GOLD_INGOT) == 0,
                "tools, food and declared work materials remain carried");
        // 手上有弓才把普通箭列为常用弹药；得到弓后重新观察能立即改变保留清单。
        inventory.add(new ItemStack(Items.BOW));
        check(amount(InventoryKeepPlan.inspect(inventory, Set.of(), List.of()), Items.ARROW) == 0, "carried ranged weapon reserves arrows");
        // 同类的一叠被命名后，当前按ID存入的后端整类保留；不能用总数去碰那叠特殊物品。
        var named = new ItemStack(Items.BONE); named.set(DataComponents.CUSTOM_NAME, Component.literal("纪念物"));
        inventory.add(named);
        check(amount(InventoryKeepPlan.inspect(inventory, Set.of(), List.of()), Items.BONE) == 0, "component variants stay outside type-only deposits");
        var reserved = InventoryKeepPlan.inspect(inventory, Set.of(Items.COBBLESTONE, Items.TORCH), List.of(Items.COBBLESTONE));
        check(amount(reserved, Items.COBBLESTONE) == 0 && amount(reserved, Items.TORCH) == 0, "explicit work reservations override ordinary stack limits");
        // 装备和副手不属于自动整理的36格范围；列表后部存在物品也不会产生存入请求。
        var extended = new ArrayList<ItemStack>(); for (int i = 0; i < 36; i++) extended.add(ItemStack.EMPTY);
        extended.add(new ItemStack(Items.DIAMOND, 10));
        check(InventoryKeepPlan.inspect(extended, Set.of(), List.of()).deposit().isEmpty(), "equipment slots are not deposits");
        check(inventory.get(4).getCount() == 64 && inventory.get(5).getCount() == 20, "planning does not mutate carried stacks");
        System.out.println("InventoryKeepPlanTest: passed");
    }
    private static int amount(InventoryKeepPlan plan, Item item) { return plan.deposit().getOrDefault(BuiltInRegistries.ITEM.getKey(item), 0); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
