// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** 无线终端工作时会耗电；恢复栏位只忽略原生电量，其余绑定、名称、数量仍必须与原终端一致。 */
final class Ae2TerminalIdentity {
    private static final ResourceLocation ENERGY = ResourceLocation.parse("ae2:stored_energy");
    private Ae2TerminalIdentity() {}

    static boolean same(ItemStack actual, ItemStack expected) {
        if (expected.isEmpty()) return actual.isEmpty();
        var id = BuiltInRegistries.ITEM.getKey(expected.getItem());
        boolean wireless = id.getNamespace().equals("ae2")
                && (id.getPath().equals("wireless_terminal") || id.getPath().equals("wireless_crafting_terminal"));
        return sameExceptEnergy(actual, expected, wireless ? BuiltInRegistries.DATA_COMPONENT_TYPE.get(ENERGY) : null);
    }

    /** 只清除明确指定的瞬时电量；换网络、改名字、替换物品或数量变化都不能被当成成功恢复。 */
    static boolean sameExceptEnergy(ItemStack actual, ItemStack expected, DataComponentType<?> energy) {
        if (expected.isEmpty()) return actual.isEmpty();
        if (actual.getCount() != expected.getCount() || !actual.is(expected.getItem())) return false;
        if (energy == null) return ItemStack.isSameItemSameComponents(actual, expected);
        ItemStack left = actual.copy(), right = expected.copy();
        left.remove(energy); right.remove(energy);
        return ItemStack.isSameItemSameComponents(left, right);
    }
}
