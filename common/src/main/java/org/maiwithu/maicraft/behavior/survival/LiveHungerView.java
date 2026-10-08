// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 饥饿处境的生产读取：饱食度、是否在掉血、身上有没有能直接吃的。
 *
 * <p>掉血判断：饱食度 0 时原版随即开始扣血，视同正在掉血；更高饱食度不掉血。
 */
public final class LiveHungerView implements HungerNeed.ReadsFacts {

    @Override
    public HungerNeed.Facts read(TickContext context) {
        LocalPlayer self = self(context);
        if (self == null) {
            return null;
        }
        int food = self.getFoodData().getFoodLevel();
        boolean carryingEdible = false;
        for (var stack : self.getInventory().items) {
            if (!stack.isEmpty() && WeaponCarriedReader.isEdible(itemId(stack))) {
                carryingEdible = true;
                break;
            }
        }
        return new HungerNeed.Facts(food, food <= 0, carryingEdible);
    }

    static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static LocalPlayer self(TickContext context) {
        return context.player() == null ? null : context.player().localPlayer();
    }
}
