// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.inventory.HotbarSwapTarget;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;

/**
 * 垫料只在主背包时挪到快捷栏的打算：寻路引擎只从快捷栏和副手拿垫块，主背包里的料它够不着，
 * 像玩家一样先打开背包把一组垫料换到快捷栏，才垫得上。
 *
 * <p>换到哪一格按 {@link HotbarSwapTarget}：先找空格，再找放着零碎东西的格子，工具、吃的、水桶、火把、
 * 正要用的东西不挪走。快捷栏没有能腾的格子就不挪，算路也不把主背包的垫料算进去。
 */
final class ScaffoldStaging {

    /** 一次挪动：主背包哪一格的什么料，换到快捷栏哪一格。 */
    record Swap(int sourceSlot, int hotbarSlot, String itemId) {}

    private static final int HOTBAR_SIZE = 9;
    private static final int LAST_MAIN_SLOT = 35;

    private ScaffoldStaging() {}

    /**
     * 要不要挪、怎么挪。快捷栏或副手已有能垫的料、主背包也没有、或快捷栏腾不出格子时为空。
     *
     * @param items    背包前 36 格：0~8 快捷栏，9~35 主背包
     * @param selected 选中的快捷栏格
     * @param spare    能垫的料，按先垫后垫排（正要用的已经扣掉）
     */
    static Optional<Swap> plan(List<ItemStack> items, ItemStack offhand, int selected,
                               Map<String, Integer> spare, ItemsToKeep keeps) {
        if (spare.isEmpty()) return Optional.empty();
        for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
            ItemStack stack = items.get(slot);
            // 快捷栏已经有能垫的：引擎自己选得到，不用开背包。
            if (!stack.isEmpty() && spare.containsKey(itemId(stack))) return Optional.empty();
        }
        if (!offhand.isEmpty() && spare.containsKey(itemId(offhand))) return Optional.empty();
        int target = HotbarSwapTarget.pick(HotbarSwapTarget.hotbar(items, keeps), selected);
        if (target < 0) return Optional.empty();
        // 按先垫后垫的次序找主背包里第一组：泥土先于圆石。
        for (String wanted : spare.keySet()) {
            for (int slot = HOTBAR_SIZE; slot < Math.min(items.size(), LAST_MAIN_SLOT + 1); slot++) {
                ItemStack stack = items.get(slot);
                if (!stack.isEmpty() && itemId(stack).equals(wanted)) {
                    return Optional.of(new Swap(slot, target, wanted));
                }
            }
        }
        return Optional.empty();
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
