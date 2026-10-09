// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.Objects;

import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 饥饿处境的生产读取：饱食度、是否在掉血、身上有没有能直接吃的。
 *
 * <p>掉血判断：饱食度 0 时原版随即开始扣血，视同正在掉血；更高饱食度不掉血。
 */
public final class LiveHungerView implements HungerNeed.ReadsFacts {

    private final ReadsFoodValues foods;

    public LiveHungerView(ReadsFoodValues foods) {
        this.foods = Objects.requireNonNull(foods, "foods");
    }

    @Override
    public HungerNeed.Facts read(TickContext context) {
        LocalPlayer self = self(context);
        if (self == null) {
            return null;
        }
        int food = self.getFoodData().getFoodLevel();
        // 身上补得最少的一件普通食物：按游戏的食物组件认，和吃饭任务挑吃的用同一条规则；
        // 趁空当吃要看它能不能整份补进去。
        BackpackView backpack = context.player().backpack();
        int smallest = backpack == null ? 0 : FoodPicker.carried(backpack.stacks(), foods).stream()
                .filter(candidate -> FoodPicker.plain(candidate.value()))
                .mapToInt(candidate -> candidate.value().nutrition())
                .min().orElse(0);
        return new HungerNeed.Facts(food, food <= 0, smallest);
    }

    private static LocalPlayer self(TickContext context) {
        return context.player() == null ? null : context.player().localPlayer();
    }
}
