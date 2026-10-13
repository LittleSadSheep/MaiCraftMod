// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;

/**
 * "能吃的口粮"这一大类：模组服里食物几十上百种，一样样点名去找是笨办法，按物品自己的食物组件认。
 * 算进来的：有食物组件、吃下去没有有害效果、普通稀有度、饱腹时不能硬吃（金苹果、紫颂果这类特殊的不算）、
 * 不是谜之炖菜（效果随机）。生肉没有有害效果也算——饿急了人也吃；生鸡肉、腐肉、蜘蛛眼带有害效果，不算。
 * 写成标签 {@code #maicraft:foods/edible}，拿东西、饿了找口粮时和别的标签一样用。
 */
public final class EverydayFood {

    /** 这一大类的标签名（不带 #）。 */
    public static final String TAG = "maicraft:foods/edible";

    private EverydayFood() {}

    /** 这件物品算不算能吃的口粮。 */
    public static boolean is(Item item) {
        ItemStack stack = new ItemStack(item);
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null || food.canAlwaysEat()) return false;
        if (stack.getRarity() != Rarity.COMMON || stack.has(DataComponents.SUSPICIOUS_STEW_EFFECTS)) return false;
        for (var possible : food.effects()) {
            if (possible.effect().getEffect().value().getCategory() == MobEffectCategory.HARMFUL) return false;
        }
        return true;
    }

    /** 注册表里所有能吃的口粮的物品 ID：按方块、作物、生物掉落反查从哪弄时用。 */
    public static List<String> all() {
        return Registered.ALL;
    }

    /** 注册表里的口粮：物品注册表进世界前就定死了，第一次用时数一遍。 */
    private static final class Registered {
        static final List<String> ALL = count();

        private static List<String> count() {
            List<String> ids = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                if (is(item)) ids.add(BuiltInRegistries.ITEM.getKey(item).toString());
            }
            return List.copyOf(ids);
        }
    }
}
