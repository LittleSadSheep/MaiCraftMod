// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 按游戏自己的食物组件读物品的数值：能不能吃、补多少、带不带效果，都问注册表里的物品组件。
 *
 * <p>查不到的物品 ID（写错、模组没装）如实回答不是食物，不猜。
 */
public final class ClientFoodValues implements ReadsFoodValues {

    private final LocalPlayer player;

    public ClientFoodValues(LocalPlayer player) {
        this.player = Objects.requireNonNull(player, "player");
    }

    @Override
    public Optional<FoodValue> of(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) return Optional.empty();
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id);
        if (item.isEmpty()) return Optional.empty();
        FoodProperties food = new ItemStack(item.get()).get(DataComponents.FOOD);
        if (food == null) return Optional.empty();
        return Optional.of(new FoodValue(itemId, food.nutrition(), food.saturation(),
                food.eatSeconds(), food.canAlwaysEat(), readEffects(food)));
    }

    // 把效果列表读成注册 ID 加益害；组件上的效果是"可能获得"，如实列出。
    private List<FoodEffect> readEffects(FoodProperties food) {
        List<FoodEffect> effects = new ArrayList<>();
        for (var possible : food.effects()) {
            MobEffectInstance instance = possible.effect();
            String name = BuiltInRegistries.MOB_EFFECT.getKey(instance.getEffect().value()).toString();
            effects.add(new FoodEffect(name, instance.getEffect().value().getCategory() != MobEffectCategory.HARMFUL));
        }
        return List.copyOf(effects);
    }
}
