// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 垫脚挑料：路上要垫方块（搭路、爬坡、垫柱子出坑）时拿什么垫。先垫随处可见又没什么用的——
 * 泥土、下界岩、安山岩闪长岩花岗岩这三样废石、凝灰岩、深板岩圆石、沙砾；圆石排在最后，
 * 它也算垃圾，但开局做石器、熔炉还要用。
 *
 * <p>正要用的东西留够数，多出来的才拿去垫：挖了三块圆石要做石镐，爬上来时不能又把这三块垫下去。
 * 要留多少由调用方按"上一个目标刚拿到的、手上这件事正在拿的"数好。
 */
public final class ScaffoldBlocks {

    /** 垫脚的料，按先垫后垫排。 */
    static final List<String> JUNK = List.of(
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

    /** 正要用的东西每种留几件；不在用的给 0。 */
    public interface Keeps {

        /** 什么都不留：没有接上"正要用的东西"时的默认。 */
        Keeps NOTHING = itemId -> 0;

        int keep(String itemId);
    }

    private ScaffoldBlocks() {}

    /**
     * 此刻能拿去垫的料，按先垫后垫排：身上这种料的件数超过要留的才算。一块都不剩时给空，
     * 路线就不靠垫方块走（能挖开的地方挖着走，走不过去如实说）。
     *
     * @param carried 身上（主背包、快捷栏与副手）每种物品一共几件
     * @param keeps   正要用的东西每种留几件
     */
    public static List<String> usable(ToIntFunction<String> carried, Keeps keeps) {
        List<String> usable = new ArrayList<>();
        for (String itemId : JUNK) {
            if (carried.applyAsInt(itemId) > keeps.keep(itemId)) usable.add(itemId);
        }
        return List.copyOf(usable);
    }
}
