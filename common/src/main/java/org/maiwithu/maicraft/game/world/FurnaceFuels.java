// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.world.item.ItemStack;

/**
 * 一件东西放进熔炉能烧多久。各加载器登记燃料的地方不一样：NeoForge 有自己的燃料数据表，
 * 原版那张燃料表里没有模组燃料；Fabric 的燃料登记表收着模组燃料。所以由加载器回答，
 * 不在公共代码里只读原版那张表。
 */
@FunctionalInterface
public interface FurnaceFuels {

    /** 放进普通熔炉能烧多少刻；不是燃料为 0。 */
    int burnTicks(ItemStack stack);
}
