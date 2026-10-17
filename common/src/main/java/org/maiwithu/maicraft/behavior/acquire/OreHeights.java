// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 主世界的矿按高度怎么分布（原版 1.18 以后的生成规则）：长程挖矿往下挖到哪一层、在哪一层横着挖，按它定。
 * 和资料 game_mechanics/ore-heights、Amaidesu 的采矿技能写的是同一份数。表里没有的矿（多是模组矿）不知道在哪层，
 * 不下探，照旧只挖看得见的。
 */
public final class OreHeights {

    /** 主世界。 */
    public static final String OVERWORLD = "minecraft:overworld";

    /**
     * 一种矿的生成范围。
     *
     * @param name      给人看的名字，例如"铁矿"
     * @param dimension 在哪个维度生成
     * @param minY      最低在哪层生成
     * @param peakY     最多的那一层：往下挖就挖到这里
     * @param maxY      最高在哪层生成
     */
    public record Band(String name, String dimension, int minY, int peakY, int maxY) {}

    private static final Band COAL = new Band("煤矿", OVERWORLD, 0, 95, 320);
    private static final Band COPPER = new Band("铜矿", OVERWORLD, -16, 48, 112);
    private static final Band IRON = new Band("铁矿", OVERWORLD, -64, 16, 320);
    private static final Band GOLD = new Band("金矿", OVERWORLD, -64, -16, 32);
    private static final Band REDSTONE = new Band("红石矿", OVERWORLD, -64, -58, 15);
    private static final Band LAPIS = new Band("青金石矿", OVERWORLD, -64, 0, 64);
    private static final Band DIAMOND = new Band("钻石矿", OVERWORLD, -64, -59, 16);

    /** 矿石方块（含深层版本）各在哪个范围。 */
    private static final Map<String, Band> BY_ORE = Map.ofEntries(
            Map.entry("minecraft:coal_ore", COAL), Map.entry("minecraft:deepslate_coal_ore", COAL),
            Map.entry("minecraft:copper_ore", COPPER), Map.entry("minecraft:deepslate_copper_ore", COPPER),
            Map.entry("minecraft:iron_ore", IRON), Map.entry("minecraft:deepslate_iron_ore", IRON),
            Map.entry("minecraft:gold_ore", GOLD), Map.entry("minecraft:deepslate_gold_ore", GOLD),
            Map.entry("minecraft:redstone_ore", REDSTONE), Map.entry("minecraft:deepslate_redstone_ore", REDSTONE),
            Map.entry("minecraft:lapis_ore", LAPIS), Map.entry("minecraft:deepslate_lapis_ore", LAPIS),
            Map.entry("minecraft:diamond_ore", DIAMOND), Map.entry("minecraft:deepslate_diamond_ore", DIAMOND));

    private OreHeights() {}

    /** 这种矿石方块的生成范围；不在表里为空。 */
    public static Optional<Band> of(String oreBlockId) {
        return Optional.ofNullable(BY_ORE.get(oreBlockId));
    }

    /**
     * 站在 feetY 要这种矿时在哪一层横着挖：在最多那层上面就往下挖到最多那层；已经在那层或以下、还在生成范围里就在原地横着挖；
     * 在生成范围以下（要往上爬才到）为空，不挖。
     */
    public static OptionalInt workingY(Band band, int feetY) {
        if (feetY > band.peakY()) return OptionalInt.of(band.peakY());
        if (feetY >= band.minY()) return OptionalInt.of(feetY);
        return OptionalInt.empty();
    }
}
