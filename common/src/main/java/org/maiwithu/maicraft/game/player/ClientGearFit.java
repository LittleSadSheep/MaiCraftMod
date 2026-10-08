// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 按游戏自己的规则读物品与装备栏的匹配：问物品在原版默认能装备到哪个栏位。
 *
 * <p>护甲、鞘翅、盾牌、可戴的头颅各自知道自己的栏位，这里只转发游戏的答案，
 * 不自己维护一份"什么能戴头上"的清单。查不到的物品 ID 什么都放不进。
 */
public final class ClientGearFit implements ReadsGearFit {

    @Override
    public boolean fits(String itemId, GearSlotName slot) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) return false;
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id);
        if (item.isEmpty()) return false;
        Equipable equipable = Equipable.get(new ItemStack(item.get()));
        if (equipable == null) return false;
        EquipmentSlot nativeSlot = equipable.getEquipmentSlot();
        return switch (slot) {
            case MAINHAND -> nativeSlot == EquipmentSlot.MAINHAND;
            case OFFHAND -> nativeSlot == EquipmentSlot.OFFHAND;
            case HEAD -> nativeSlot == EquipmentSlot.HEAD;
            case CHEST -> nativeSlot == EquipmentSlot.CHEST;
            case LEGS -> nativeSlot == EquipmentSlot.LEGS;
            case FEET -> nativeSlot == EquipmentSlot.FEET;
        };
    }
}
