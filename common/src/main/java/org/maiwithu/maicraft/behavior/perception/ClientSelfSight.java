// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 角色自身观察的读端：每刻从当刻的角色上下文里读一次位置、视角、生命、饥饿、氧气、
 * 手持、护甲、状态效果、落地与入水，以及装备与手里东西自己画的那条，交给感知的场景整理。
 *
 * <p>只在控制循环的刻内调用（上下文只在当刻有效）；没有角色上下文时返回 null，
 * 调用方这一刻跳过自身观察，等其他观察有基准后再整理。
 */
public final class ClientSelfSight implements SelfSight {

    /** 物品条画满是 13 像素（原版 ItemStack.getBarWidth 的上限）。 */
    private static final float FULL_BAR = 13f;

    private final Supplier<PlayerContext> context;

    public ClientSelfSight(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public Facts current() {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null) {
            return null;
        }
        LocalPlayer player = current.localPlayer();
        List<String> armor = new ArrayList<>();
        for (ItemStack piece : player.getArmorSlots()) {
            if (!piece.isEmpty()) {
                armor.add(itemTypeId(piece));
            }
        }
        List<String> effects = new ArrayList<>();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            // 状态效果给玩家看得懂的名字；强度与剩余时刻在这里不展开，够认出状态就行。
            effects.add(effect.getEffect().value().getDisplayName().getString());
        }
        return new Facts(
                player.getX(), player.getY(), player.getZ(), player.getYRot(),
                (int) player.getHealth(), player.getFoodData().getFoodLevel(),
                player.getAirSupply(), player.getMaxAirSupply(),
                heldItem(player), armor, effects,
                player.onGround(), player.isInWater(), gearBars(player),
                // 副手单独报：整理背包、换手时看得出副手攥着什么（盾、火把、顺手捡的零碎）。
                player.getOffhandItem().isEmpty() ? null : itemTypeId(player.getOffhandItem()));
    }

    // 六处装备格里画着条的东西：物品自己说画不画（isBarVisible）、画多长（满长 13），换成百分比，
    // 耐久、背罐的气、电量一视同仁，不认是哪种。
    private static List<SelfSight.GearBar> gearBars(LocalPlayer player) {
        List<SelfSight.GearBar> bars = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.HAND && slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) continue;
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty() || !stack.isBarVisible()) continue;
            bars.add(new SelfSight.GearBar(slot.getName(), itemTypeId(stack), Math.round(stack.getBarWidth() * 100f / FULL_BAR)));
        }
        return bars;
    }

    // 主手优先，主手空着看副手；两手都空是"没拿东西"而不是读不到。
    private static String heldItem(LocalPlayer player) {
        if (!player.getMainHandItem().isEmpty()) {
            return itemTypeId(player.getMainHandItem());
        }
        if (!player.getOffhandItem().isEmpty()) {
            return itemTypeId(player.getOffhandItem());
        }
        return null;
    }

    private static String itemTypeId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
