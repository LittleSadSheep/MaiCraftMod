// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.inventory;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.block.TorchBlock;

/** 先保留当前工作与随身应急用品，再选可存物；此处只制定清单，不移动或丢弃物品。 */
public record InventoryKeepPlan(Map<ResourceLocation, Integer> deposit, Map<ResourceLocation, String> retainedReasons) {
    public InventoryKeepPlan { deposit = Map.copyOf(deposit); retainedReasons = Map.copyOf(retainedReasons); }

    public static InventoryKeepPlan inspect(List<ItemStack> inventory, Set<Item> workItems, List<Item> supportItems) {
        var counts = new LinkedHashMap<Item, Integer>();
        var protectedItems = new LinkedHashSet<Item>(workItems);
        var reasons = new LinkedHashMap<ResourceLocation, String>();
        boolean ranged = inventory.stream().anyMatch(stack -> stack.getItem() instanceof ProjectileWeaponItem);
        for (int slot = 0; slot < Math.min(36, inventory.size()); slot++) {
            ItemStack stack = inventory.get(slot); if (stack.isEmpty()) continue;
            counts.merge(stack.getItem(), stack.getCount(), Math::addExact);
            // 当前存入后端按物品类型选叠；有组件或单件工具时整类留下，防止把携带中的终端、背包和工具一起存走。
            if (!stack.getComponentsPatch().isEmpty() || stack.getMaxStackSize() == 1) {
                protectedItems.add(stack.getItem()); reasons.put(id(stack.getItem()), "tool_equipment_or_component_identity");
            }
        }
        workItems.forEach(item -> { if (counts.containsKey(item)) reasons.put(id(item), "current_or_planned_work"); });
        // 垫脚材料只保留一类的一组，避免十几种零散土石长期占满背包；明确工作材料不受一组上限影响。
        Item support = supportItems.stream().filter(item -> counts.getOrDefault(item, 0) > 0 && !protectedItems.contains(item))
                .max(Comparator.comparingInt(item -> counts.getOrDefault(item, 0))).orElse(null);
        var deposits = new LinkedHashMap<ResourceLocation, Integer>();
        for (var entry : counts.entrySet()) {
            Item item = entry.getKey(); int count = entry.getValue();
            if (protectedItems.contains(item)) continue;
            ItemStack sample = new ItemStack(item); int keep = 0;
            if (sample.has(DataComponents.FOOD)) {
                keep = Math.min(count, sample.getMaxStackSize()); reasons.put(id(item), "carried_food");
            } else if (item instanceof BlockItem block && block.getBlock() instanceof TorchBlock) {
                keep = Math.min(count, sample.getMaxStackSize()); reasons.put(id(item), "lighting");
            } else if (item == Items.BUCKET) {
                keep = Math.min(count, 1); reasons.put(id(item), "emergency_bucket");
            } else if (item == Items.COAL || item == Items.CHARCOAL) {
                keep = Math.min(count, 64); reasons.put(id(item), "ordinary_fuel");
            } else if (ranged && (item == Items.ARROW || item == Items.SPECTRAL_ARROW)) {
                keep = Math.min(count, 64); reasons.put(id(item), "carried_weapon_ammunition");
            }
            if (item == support) { keep = Math.max(keep, Math.min(count, 64)); reasons.put(id(item), "temporary_support"); }
            if (count > keep) deposits.put(id(item), count - keep);
        }
        return new InventoryKeepPlan(deposits, reasons);
    }

    private static ResourceLocation id(Item item) { return BuiltInRegistries.ITEM.getKey(item); }
}
