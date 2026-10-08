// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import java.util.List;
import java.util.Optional;

/**
 * 食物数值只读视图：一种物品按游戏自己的食物组件读出来的事实。
 *
 * <p>能不能吃、吃了补多少、吃完带不带状态效果，都是物品组件里写着的游戏事实，
 * 全仓只在这里读一次；挑哪种吃是判断类的事，它只认这里给的事实。
 */
public interface ReadsFoodValues {

    /** 一种物品的食物数值；不是食物（没有食物组件）时为 empty。 */
    Optional<FoodValue> of(String itemId);

    /**
     * 一种食物的数值事实。
     *
     * @param itemId       物品注册 ID
     * @param nutrition    营养值：吃下去恢复多少饱食度
     * @param saturation   饱和度：营养之外再补多少，决定饱腹能撑多久
     * @param eatSeconds   吃一件要几秒
     * @param canAlwaysEat 饱食度满时能不能照吃（金苹果这类，饱腹也能吃）
     * @param effects      吃下去可能获得的状态效果；普通食物为空列表
     */
    record FoodValue(
            String itemId, int nutrition, float saturation, float eatSeconds,
            boolean canAlwaysEat, List<FoodEffect> effects) {

        public FoodValue {
            effects = List.copyOf(effects);
        }

        /** 饱食度没满时能不能开始吃：普通食物可以，满饱食度只留"饱腹也能吃"的。 */
        public boolean edibleWhenFull() {
            return canAlwaysEat;
        }
    }

    /**
     * 吃下去可能获得的一种状态效果。
     *
     * @param name       效果的注册 ID，例如 minecraft:regeneration
     * @param beneficial 对身体有益还是有害；腐肉的食物中毒是害
     */
    record FoodEffect(String name, boolean beneficial) {
    }
}
