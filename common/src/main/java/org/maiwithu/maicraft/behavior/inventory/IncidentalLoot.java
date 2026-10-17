// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.List;

/**
 * 顺手捡的零碎是哪些：打怪、砍树、走路时意料之外捡到、又没什么用的战利品。腾背包时垃圾之后先丢它们，
 * 做出来的零件、链条、桶这类有用的留着（实机腾背包丢了链条、桶、玫瑰石英，却留着蜘蛛眼、蘑菇、生羊肉、树苗）。
 * 这次任务要用的不算（任务留用压过一切）。
 */
final class IncidentalLoot {

    /** 点名的零碎，先丢的排前面。 */
    static final List<String> ITEMS = List.of(
            "minecraft:spider_eye",
            "minecraft:poisonous_potato",
            "minecraft:brown_mushroom",
            "minecraft:red_mushroom",
            "minecraft:crimson_fungus",
            "minecraft:warped_fungus",
            "minecraft:egg",
            "minecraft:ink_sac",
            "minecraft:bone",
            "minecraft:string",
            "minecraft:feather",
            "minecraft:beef",
            "minecraft:porkchop",
            "minecraft:mutton",
            "minecraft:chicken",
            "minecraft:rabbit",
            "minecraft:cod",
            "minecraft:salmon",
            "minecraft:tropical_fish",
            "minecraft:pufferfish",
            "minecraft:short_grass",
            "minecraft:tall_grass",
            "minecraft:fern",
            "minecraft:large_fern",
            "minecraft:dead_bush",
            "minecraft:vine",
            "minecraft:lily_pad",
            "minecraft:dandelion",
            "minecraft:poppy",
            "minecraft:blue_orchid",
            "minecraft:allium",
            "minecraft:azure_bluet",
            "minecraft:red_tulip",
            "minecraft:orange_tulip",
            "minecraft:white_tulip",
            "minecraft:pink_tulip",
            "minecraft:oxeye_daisy",
            "minecraft:cornflower",
            "minecraft:lily_of_the_valley",
            "minecraft:sunflower",
            "minecraft:lilac",
            "minecraft:rose_bush",
            "minecraft:peony");

    private IncidentalLoot() {}

    /** 是不是顺手捡的零碎：点名的那些，加上各模组的树苗、红树胎生苗与种子。 */
    static boolean is(String itemId) {
        return ITEMS.contains(itemId) || itemId.endsWith("_sapling") || itemId.endsWith("_propagule")
                || itemId.endsWith("_seeds");
    }

    /** 丢的先后：点名的按清单，树苗、种子排在点名的后面。 */
    static int dropOrder(String itemId) {
        int index = ITEMS.indexOf(itemId);
        return index < 0 ? ITEMS.size() : index;
    }
}
