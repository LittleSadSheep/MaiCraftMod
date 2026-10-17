// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.backpack;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem;

import org.maiwithu.maicraft.compat.backpack.BackpackItems;

/**
 * 精妙背包的物品读写端：看注册表里这个 ID 的物品是不是它的 BackpackItem。只翻译，不判断。
 * 直接引用模组的类，所以只在联动清单确认装了、版本在范围内之后才会被加载。
 */
public final class SophisticatedBackpackItems implements BackpackItems {

    @Override public boolean isBackpack(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) {
            return false;
        }
        return BuiltInRegistries.ITEM.getOptional(id).map(item -> item instanceof BackpackItem).orElse(false);
    }
}
