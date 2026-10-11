// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * 随身物品的读取：把角色背包与手里的东西整理成武器挑选要看的清单。
 *
 * <p>只读，不改背包不换手上物品；弩是否已上弦按物品组件读，弓箭看背包里有没有箭。
 */
public final class WeaponCarriedReader {

    private WeaponCarriedReader() {}

    /** 背包与双手的清单：物品注册 ID、数量、弩是否已上弦、在哪一格、拿在主手时的攻击伤害与攻速。 */
    public static List<WeaponChoice.Carried> read(LocalPlayer player) {
        List<WeaponChoice.Carried> carried = new ArrayList<>();
        var items = player.getInventory().items;
        for (int slot = 0; slot < items.size(); slot++) {
            if (!items.get(slot).isEmpty()) {
                carried.add(asCarried(items.get(slot), slot));
            }
        }
        if (!player.getOffhandItem().isEmpty()) {
            carried.add(asCarried(player.getOffhandItem(), OFFHAND_SLOT));
        }
        return carried;
    }

    /** 副手在背包里的格号。 */
    static final int OFFHAND_SLOT = 40;
    /** 空手的攻击伤害与每秒攻击次数（原版玩家属性的基础值）。 */
    private static final double BASE_DAMAGE = 1.0;
    private static final double BASE_SPEED = 4.0;

    /** 背包里有没有箭：弓要用它才拉得开。 */
    public static boolean hasArrows(LocalPlayer player) {
        for (ItemStack stack : player.getInventory().items) {
            if (isArrow(stack)) {
                return true;
            }
        }
        return isArrow(player.getOffhandItem());
    }

    private static WeaponChoice.Carried asCarried(ItemStack stack, int slot) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        boolean charged = false;
        if (stack.getItem() instanceof CrossbowItem) {
            // 已上弦的弩带已装填的箭组件；读不到组件时按没上弦处理，宁可低估。
            var projectiles = stack.get(DataComponents.CHARGED_PROJECTILES);
            charged = projectiles != null && !projectiles.isEmpty();
        }
        // 拿在主手时的攻击伤害与攻速：按物品自己的攻击属性加到空手的基础值上（锋利这类附魔不算）。
        double damage = BASE_DAMAGE;
        double speed = BASE_SPEED;
        var modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        for (var entry : modifiers.modifiers()) {
            if (!entry.slot().test(EquipmentSlot.MAINHAND)
                    || entry.modifier().operation() != AttributeModifier.Operation.ADD_VALUE) continue;
            if (entry.attribute().is(Attributes.ATTACK_DAMAGE)) damage += entry.modifier().amount();
            if (entry.attribute().is(Attributes.ATTACK_SPEED)) speed += entry.modifier().amount();
        }
        return new WeaponChoice.Carried(id, stack.getCount(), charged, slot, damage, Math.max(0.1, speed));
    }

    private static boolean isArrow(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ArrowItem;
    }
}
