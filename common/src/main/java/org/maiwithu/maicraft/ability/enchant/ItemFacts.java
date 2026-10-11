// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.enchant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * 一格物品的可附魔事实：是什么、几件、挂着什么附魔、存着什么附魔、其余组件的指纹。
 *
 * <p>附魔组件之外的一切（损伤、名字、其他模组组件）在附魔前后必须原样保留，
 * 成品判据靠这份指纹分辨「只附了魔」和「变成了别的东西」。
 */
record ItemFacts(String itemId, int count, Map<String, Integer> enchantments,
                 Map<String, Integer> storedEnchantments, String otherComponents) {

    static final ItemFacts EMPTY = new ItemFacts("minecraft:air", 0, Map.of(), Map.of(), "");

    ItemFacts {
        enchantments = Map.copyOf(enchantments);
        storedEnchantments = Map.copyOf(storedEnchantments);
    }

    /** 从游戏的一叠物品读出事实；只读现场，不预测。 */
    static ItemFacts of(ItemStack stack) {
        if (stack.isEmpty()) return EMPTY;
        return new ItemFacts(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getCount(), enchantments(stack.get(DataComponents.ENCHANTMENTS)),
                enchantments(stack.get(DataComponents.STORED_ENCHANTMENTS)),
                otherComponents(stack));
    }

    /** 台子肯不肯收：已附魔或含储存附魔（附魔书、已附的装备）的不再接受第二次。 */
    boolean unenchanted() {
        return enchantments.isEmpty() && storedEnchantments.isEmpty();
    }

    boolean isEmpty() {
        return count <= 0 || itemId.equals("minecraft:air");
    }

    /** 附魔条目的说法，例如 "minecraft:sharpness 3"；书用储存附魔，装备用附魔组件。 */
    List<String> enchantmentLines() {
        List<String> lines = new ArrayList<>();
        enchantments.forEach((id, level) -> lines.add(id + " " + level));
        storedEnchantments.forEach((id, level) -> lines.add(id + " " + level));
        lines.sort(Comparator.naturalOrder());
        return lines;
    }

    /** 附魔组件换成「附魔 ID 等级」的普通映射，空的给空表。 */
    private static Map<String, Integer> enchantments(ItemEnchantments enchantments) {
        if (enchantments == null || enchantments.isEmpty()) return Map.of();
        Map<String, Integer> map = new LinkedHashMap<>();
        enchantments.entrySet().forEach(entry -> map.put(
                entry.getKey().unwrapKey().map(key -> key.location().toString()).orElse(""),
                entry.getIntValue()));
        return map;
    }

    /** 附魔之外的全部组件读成排序指纹；损伤、名字等任何一样变了指纹就变。 */
    private static String otherComponents(ItemStack stack) {
        List<String> parts = new ArrayList<>();
        stack.getComponents().forEach(typed -> {
            if (typed.type() != DataComponents.ENCHANTMENTS && typed.type() != DataComponents.STORED_ENCHANTMENTS) {
                parts.add(String.valueOf(typed));
            }
        });
        parts.sort(Comparator.naturalOrder());
        return String.join("|", parts);
    }
}
