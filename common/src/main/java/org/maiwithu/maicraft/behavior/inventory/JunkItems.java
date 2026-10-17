// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.List;

/**
 * 垃圾是哪几样，全仓只有这一份：腾背包时先丢它、路上垫方块先用它。
 *
 * <p>随处可见又没什么用的方块：泥土一类、下界岩、安山岩闪长岩花岗岩这三样废石、凝灰岩、深板岩圆石、沙砾；
 * 圆石也算，但开局做石器、熔炉还要用，排在最后，背包里一组以内还当建材留着。腐肉不是方块，只在丢的时候算垃圾，而且最先丢。
 * 原先两处各写一份：腾背包的那份没有三废石、把圆石和泥土放在一起，于是先丢马上要用的圆石、留着三废石。
 */
public final class JunkItems {

    /** 垃圾方块，先用（先丢、先垫）的排前面。 */
    public static final List<String> BLOCKS = List.of(
            "minecraft:dirt",
            "minecraft:coarse_dirt",
            "minecraft:rooted_dirt",
            "minecraft:netherrack",
            "minecraft:andesite",
            "minecraft:diorite",
            "minecraft:granite",
            "minecraft:tuff",
            "minecraft:cobbled_deepslate",
            "minecraft:gravel",
            "minecraft:cobblestone");

    private static final String ROTTEN_FLESH = "minecraft:rotten_flesh";
    private static final String COBBLESTONE = "minecraft:cobblestone";

    private JunkItems() {}

    /** 是不是垃圾这一类（不看数量）。 */
    public static boolean isJunk(String itemId) {
        return ROTTEN_FLESH.equals(itemId) || BLOCKS.contains(itemId);
    }

    /**
     * 按背包里的总数算是不是垃圾：圆石一组以内还当建材，超过一组的那些才算；其余垃圾方块与腐肉多少都算。
     *
     * @param totalCarried 背包里这种物品一共几件
     * @param maxStack     这种物品一组几件
     */
    public static boolean junkAt(String itemId, int totalCarried, int maxStack) {
        if (!isJunk(itemId)) return false;
        return !COBBLESTONE.equals(itemId) || totalCarried > maxStack;
    }

    /** 丢东西时的先后：腐肉最先，再按垃圾方块的先后，圆石最后；不是垃圾排在所有垃圾后面。 */
    public static int dropOrder(String itemId) {
        if (ROTTEN_FLESH.equals(itemId)) return 0;
        int index = BLOCKS.indexOf(itemId);
        return index < 0 ? BLOCKS.size() + 1 : index + 1;
    }
}
