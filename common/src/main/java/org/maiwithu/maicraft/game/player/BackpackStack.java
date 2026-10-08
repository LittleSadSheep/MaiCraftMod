// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

/**
 * 背包一格里的物品：只读的事实快照，物品是什么、有多少、能叠多少，加上腾地方要用的几个分类标记。
 *
 * <p>分类标记问的是游戏本身：拿在手上是装备还是吃的、稀不稀罕、是不是整块的建材。
 * 拿这些标记去排"先丢谁"是玩家行为层的事，这里只给事实。
 *
 * @param itemId           物品的注册 ID，例如 {@code minecraft:iron_pickaxe}
 * @param count            这一格里有多少件
 * @param maxStackSize     这种物品一格最多能叠多少件
 * @param gear             是工具、武器或护甲：玩家靠它干活和保命，腾地方时不会先动它
 * @param food             能吃：饿的时候要靠它
 * @param precious         稀有贵重：带附魔或游戏自己标了稀有度，丢了很难补回来
 * @param buildingMaterial 是整块的建材（圆石、木板、泥土这类）：能搭能垫，也最容易再弄到
 */
public record BackpackStack(
        String itemId,
        int count,
        int maxStackSize,
        boolean gear,
        boolean food,
        boolean precious,
        boolean buildingMaterial) {

    public BackpackStack {
        if (itemId == null || itemId.isBlank()) throw new IllegalArgumentException("物品的注册 ID 不能为空");
        if (count <= 0) throw new IllegalArgumentException("一格里的物品数量必须为正：" + count);
        if (maxStackSize <= 0) throw new IllegalArgumentException("物品的最大堆叠必须为正：" + maxStackSize);
    }
}
