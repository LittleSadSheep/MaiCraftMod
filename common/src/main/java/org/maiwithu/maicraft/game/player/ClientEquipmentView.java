// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * 读真实角色的装备栏：每次读取都是当下的一张快照，不缓存。
 *
 * <p>主手读选中的快捷栏格，副手与四格护甲读各自的装备栏格；
 * 每格交给与背包视图同一份快照逻辑，分类标记不各读各的。
 */
public final class ClientEquipmentView implements ReadsEquipment {

    private final LocalPlayer player;

    public ClientEquipmentView(LocalPlayer player) {
        this.player = Objects.requireNonNull(player, "player");
    }

    @Override
    public Optional<BackpackStack> slot(GearSlotName name) {
        ItemStack stack = player.getItemBySlot(mapped(name));
        return stack.isEmpty() ? Optional.empty() : Optional.of(ClientBackpackView.snapshot(stack));
    }

    // 本项目的栏位名与原版 EquipmentSlot 一一对应，只做一次映射。
    private static EquipmentSlot mapped(GearSlotName name) {
        return switch (name) {
            case MAINHAND -> EquipmentSlot.MAINHAND;
            case OFFHAND -> EquipmentSlot.OFFHAND;
            case HEAD -> EquipmentSlot.HEAD;
            case CHEST -> EquipmentSlot.CHEST;
            case LEGS -> EquipmentSlot.LEGS;
            case FEET -> EquipmentSlot.FEET;
        };
    }
}
