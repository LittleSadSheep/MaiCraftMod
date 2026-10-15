// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * 主背包里的东西要换到快捷栏时换进哪一格，全仓只有这一份：走路要垫的料、挖路的工具、打架的武器都按它挑。
 *
 * <p>空格先于放着零碎东西的格子；有耐久的（工具、武器、盔甲、盾、打火石）、能吃的、桶、火把、不死图腾、
 * 正要用的东西不换走——换走了挖方块没镐子、掉下去没水桶、打完架挖矿又得开背包把镐换回来。
 * 同类里先挑没选中的、靠右的格子，尽量不动手里拿着的。
 */
public final class HotbarSwapTarget {

    /** 快捷栏一格放着什么：空的、能腾出来的、不能动的。 */
    public enum Holding { EMPTY, MOVABLE, KEEP }

    /** 原版换快捷栏的点击只认这九格。 */
    private static final int HOTBAR_SIZE = 9;

    private HotbarSwapTarget() {}

    /** 快捷栏九格各放着什么。 */
    public static List<Holding> hotbar(List<ItemStack> items, ItemsToKeep keeps) {
        List<Holding> hotbar = new ArrayList<>();
        for (int slot = 0; slot < HOTBAR_SIZE && slot < items.size(); slot++) hotbar.add(holding(items.get(slot), keeps));
        return hotbar;
    }

    /**
     * 快捷栏里换哪一格：空格先于零碎东西，同类里没选中的先于选中的、靠右的先于靠左的；
     * 全是不能动的东西时为 -1。
     */
    public static int pick(List<Holding> hotbar, int selected) {
        for (Holding wanted : List.of(Holding.EMPTY, Holding.MOVABLE)) {
            int fallback = -1;
            for (int slot = hotbar.size() - 1; slot >= 0; slot--) {
                if (hotbar.get(slot) != wanted) continue;
                if (slot != selected) return slot;
                fallback = slot;
            }
            if (fallback >= 0) return fallback;
        }
        return -1;
    }

    /** 有耐久的（工具、武器、盔甲、盾、打火石）、能吃的、桶、火把、不死图腾、正要用的不挪；别的零碎可以换进背包。 */
    public static Holding holding(ItemStack stack, ItemsToKeep keeps) {
        if (stack.isEmpty()) return Holding.EMPTY;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        boolean keep = stack.isDamageableItem() || stack.has(DataComponents.FOOD)
                || id.endsWith("bucket") || id.endsWith("torch") || id.equals("minecraft:totem_of_undying")
                || keeps.keep(id) > 0;
        return keep ? Holding.KEEP : Holding.MOVABLE;
    }
}
