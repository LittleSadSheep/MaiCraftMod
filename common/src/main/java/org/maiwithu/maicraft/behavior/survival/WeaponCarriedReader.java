// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

/**
 * 随身物品的读取：把角色背包与手里的东西整理成武器挑选要看的清单。
 *
 * <p>只读，不改背包不换手上物品；弩是否已上弦按物品组件读，弓箭看背包里有没有箭。
 */
public final class WeaponCarriedReader {

    private WeaponCarriedReader() {}

    /** 背包与双手的清单：物品注册 ID、数量，弩是否已上弦。 */
    static List<WeaponChoice.Carried> read(LocalPlayer player) {
        List<WeaponChoice.Carried> carried = new ArrayList<>();
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty()) {
                carried.add(asCarried(stack));
            }
        }
        if (!player.getOffhandItem().isEmpty()) {
            carried.add(asCarried(player.getOffhandItem()));
        }
        return carried;
    }

    /** 背包里有没有箭：弓要用它才拉得开。 */
    static boolean hasArrows(LocalPlayer player) {
        for (ItemStack stack : player.getInventory().items) {
            if (isArrow(stack)) {
                return true;
            }
        }
        return isArrow(player.getOffhandItem());
    }

    private static WeaponChoice.Carried asCarried(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        boolean charged = false;
        if (stack.getItem() instanceof CrossbowItem) {
            // 已上弦的弩带已装填的箭组件；读不到组件时按没上弦处理，宁可低估。
            charged = stack.get(DataComponents.CHARGED_PROJECTILES) != null;
        }
        return new WeaponChoice.Carried(id, stack.getCount(), charged);
    }

    private static boolean isArrow(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ArrowItem;
    }
}
