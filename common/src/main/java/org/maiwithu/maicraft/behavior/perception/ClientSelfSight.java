// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 角色自身观察的读端：每刻从当刻的角色上下文里读一次位置、视角、生命、饥饿、氧气、
 * 手持、护甲、状态效果、落地与入水，交给感知的场景整理。
 *
 * <p>只在控制循环的刻内调用（上下文只在当刻有效）；没有角色上下文时返回 null，
 * 调用方这一刻跳过自身观察，等其他观察有基准后再整理。
 */
public final class ClientSelfSight implements SelfSight {

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
                player.onGround(), player.isInWater());
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
