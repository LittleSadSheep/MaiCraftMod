// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 原版"挖掉哪种方块掉什么"的常识对照：问一件东西时，先在这里查哪些方块会掉它。
 *
 * <p>客户端读不到方块的掉落表（那是服务端的数据），采掘扫描只靠这一份写死的原版对照
 * 与"同一种方块挖掉掉自己"的通例。覆盖不到的模组方块如实回答"没有方块直接掉出"，
 * 不冒充找遍了；对照只写方块与掉落的对应关系，不写掉率与工具要求，那些由游戏现场说了算。
 */
public final class VanillaBlockDrops {

    /** 不会掉自己的方块：挖掉掉的是别的（石头掉圆石这类），按这份对照查。 */
    private static final Map<String, String> BLOCK_TO_DROP = Map.ofEntries(
            Map.entry("minecraft:stone", "minecraft:cobblestone"),
            Map.entry("minecraft:deepslate", "minecraft:cobbled_deepslate"),
            Map.entry("minecraft:grass_block", "minecraft:dirt"),
            Map.entry("minecraft:mycelium", "minecraft:dirt"),
            Map.entry("minecraft:podzol", "minecraft:dirt"),
            Map.entry("minecraft:coal_ore", "minecraft:coal"),
            Map.entry("minecraft:deepslate_coal_ore", "minecraft:coal"),
            Map.entry("minecraft:iron_ore", "minecraft:raw_iron"),
            Map.entry("minecraft:deepslate_iron_ore", "minecraft:raw_iron"),
            Map.entry("minecraft:copper_ore", "minecraft:raw_copper"),
            Map.entry("minecraft:deepslate_copper_ore", "minecraft:raw_copper"),
            Map.entry("minecraft:gold_ore", "minecraft:raw_gold"),
            Map.entry("minecraft:deepslate_gold_ore", "minecraft:raw_gold"),
            Map.entry("minecraft:nether_gold_ore", "minecraft:gold_nugget"),
            Map.entry("minecraft:diamond_ore", "minecraft:diamond"),
            Map.entry("minecraft:deepslate_diamond_ore", "minecraft:diamond"),
            Map.entry("minecraft:emerald_ore", "minecraft:emerald"),
            Map.entry("minecraft:deepslate_emerald_ore", "minecraft:emerald"),
            Map.entry("minecraft:lapis_ore", "minecraft:lapis_lazuli"),
            Map.entry("minecraft:deepslate_lapis_ore", "minecraft:lapis_lazuli"),
            Map.entry("minecraft:redstone_ore", "minecraft:redstone"),
            Map.entry("minecraft:deepslate_redstone_ore", "minecraft:redstone"),
            Map.entry("minecraft:nether_quartz_ore", "minecraft:quartz"),
            Map.entry("minecraft:amethyst_cluster", "minecraft:amethyst_shard"),
            Map.entry("minecraft:glowstone", "minecraft:glowstone_dust"),
            Map.entry("minecraft:clay", "minecraft:clay_ball"));

    /** 名字对得上方块、挖掉却不掉自己的例外：玻璃碎了什么都没有，冰化成水，雪堆掉雪球。 */
    private static final List<String> DROPS_NOTHING = List.of(
            "minecraft:glass", "minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice",
            "minecraft:snow", "minecraft:snow_block");

    /**
     * 谁都知道埋在脚下的石头：地表往下挖几格就有，不用先看见。
     * 只有它是例外，矿石（煤、铁、钻石）照旧要看得见才去挖。
     */
    private static final String BURIED_STONE = "minecraft:stone";

    private VanillaBlockDrops() {}

    /** 这件东西是不是埋在脚下的石头掉的（圆石）：看不见石头时也能往下挖楼梯去找。 */
    public static boolean buriedUnderfoot(String itemId) {
        return blocksDropping(itemId).contains(BURIED_STONE);
    }

    /** 挖掉会掉出这件东西的方块类型；对照里没有、也不掉自己的物品给空。 */
    public static List<String> blocksDropping(String itemId) {
        if (DROPS_NOTHING.contains(itemId)) {
            return List.of();
        }
        List<String> blockTypes = new ArrayList<>();
        for (var entry : BLOCK_TO_DROP.entrySet()) {
            if (entry.getValue().equals(itemId)) {
                blockTypes.add(entry.getKey());
            }
        }
        // 掉自己的通例：方块注册里与物品同名的那些（泥土、圆石、原木这类），挖掉掉自己；
        // 对照里已有条目的也一并算上（泥土既掉自泥土也掉自草方块）。没有同名方块的写法
        // 在扫描里解析不到方块，自然落空，不碍事。对照里写明掉别的（石头掉圆石、矿石掉矿物）
        // 的方块不掉自己：要石头不能去挖石头，挖了拿到的是圆石。
        if (!BLOCK_TO_DROP.containsKey(itemId)) {
            blockTypes.add(itemId);
        }
        return List.copyOf(blockTypes);
    }

    /** 挖掉这种方块掉什么；对照里没有的按"掉自己"的通例回答。 */
    public static String droppedBy(String blockTypeId) {
        return BLOCK_TO_DROP.getOrDefault(blockTypeId, blockTypeId);
    }
}
