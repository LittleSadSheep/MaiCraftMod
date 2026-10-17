// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.enchant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 成品判据的离线场景：书转附魔书、装备组件保真、线索必须在成品里；
 * 成品与费用同刻吻合才算确认，分包抵达就继续等，变成预料之外不算数。
 */
class EnchantedResultCheckTest {

    private static final ItemFacts SWORD = new ItemFacts("minecraft:iron_sword", 1,
            Map.of(), Map.of(), "damage=0|name=无名");
    private static final ItemFacts BOOK = new ItemFacts("minecraft:book", 1,
            Map.of(), Map.of(), "plain");

    @Test
    void 书附成附魔书线索在储存附魔里() {
        var enchantedBook = new ItemFacts("minecraft:enchanted_book", 1,
                Map.of(), Map.of("minecraft:sharpness", 3), "plain");
        assertEquals(EnchantedResultCheck.Verdict.CONFIRMED,
                EnchantedResultCheck.judge(BOOK, enchantedBook, "minecraft:sharpness", 3));
    }

    @Test
    void 书没变成附魔书是预料之外() {
        assertEquals(EnchantedResultCheck.Verdict.DIVERGED,
                EnchantedResultCheck.judge(BOOK, BOOK, "minecraft:sharpness", 3));
    }

    @Test
    void 装备只许附魔组件变化损伤名字原样保留() {
        var enchanted = new ItemFacts("minecraft:iron_sword", 1,
                Map.of("minecraft:sharpness", 3), Map.of(), "damage=0|name=无名");
        assertEquals(EnchantedResultCheck.Verdict.CONFIRMED,
                EnchantedResultCheck.judge(SWORD, enchanted, "minecraft:sharpness", 3));
        // 损伤或名字变了：变成预料之外，不冒充只附了魔。
        var renamed = new ItemFacts("minecraft:iron_sword", 1,
                Map.of("minecraft:sharpness", 3), Map.of(), "damage=0|name=屠龙刀");
        assertEquals(EnchantedResultCheck.Verdict.DIVERGED,
                EnchantedResultCheck.judge(SWORD, renamed, "minecraft:sharpness", 3));
    }

    @Test
    void 线索必须真的在成品里不在就继续等() {
        var empty = new ItemFacts("minecraft:iron_sword", 1, Map.of(), Map.of(), "damage=0|name=无名");
        assertEquals(EnchantedResultCheck.Verdict.PENDING,
                EnchantedResultCheck.judge(SWORD, empty, "minecraft:sharpness", 3));
        // 线索在但等级对不上：不是这一档的成果。
        var wrongLevel = new ItemFacts("minecraft:iron_sword", 1,
                Map.of("minecraft:sharpness", 1), Map.of(), "damage=0|name=无名");
        assertEquals(EnchantedResultCheck.Verdict.PENDING,
                EnchantedResultCheck.judge(SWORD, wrongLevel, "minecraft:sharpness", 3));
    }

    @Test
    void 成品与费用同刻吻合才算确认() {
        var snapshot = new EnchantMenuFacts.Snapshot(new int[3], new String[3], new int[3], 1,
                new ItemFacts("minecraft:iron_sword", 1, Map.of("minecraft:sharpness", 3), Map.of(),
                        "damage=0|name=无名"),
                new ItemFacts("minecraft:lapis_lazuli", 1, Map.of(), Map.of(), ""), true, 27, false);
        var offer = new TierOffer(3, 30, 3, 3, "minecraft:sharpness", 3);
        // 按第三档：青金石从 4 扣到 1、等级从 30 扣到 27，成品在线索在——三方面齐了才算。
        assertEquals(EnchantedResultCheck.Verdict.CONFIRMED,
                EnchantedResultCheck.judgeWithCost(snapshot, SWORD, offer, 1, 27));
    }

    @Test
    void 分包抵达就继续等费用没齐不算数() {
        var offer = new TierOffer(3, 30, 3, 3, "minecraft:sharpness", 3);
        // 成品先到、青金石还没扣完：继续等。
        var lapisLate = new EnchantMenuFacts.Snapshot(new int[3], new String[3], new int[3], 1,
                new ItemFacts("minecraft:iron_sword", 1, Map.of("minecraft:sharpness", 3), Map.of(),
                        "damage=0|name=无名"),
                new ItemFacts("minecraft:lapis_lazuli", 3, Map.of(), Map.of(), ""), true, 27, false);
        assertEquals(EnchantedResultCheck.Verdict.PENDING,
                EnchantedResultCheck.judgeWithCost(lapisLate, SWORD, offer, 1, 27));
        // 成品到了、等级还没扣：继续等。
        var levelLate = new EnchantMenuFacts.Snapshot(new int[3], new String[3], new int[3], 1,
                new ItemFacts("minecraft:iron_sword", 1, Map.of("minecraft:sharpness", 3), Map.of(),
                        "damage=0|name=无名"),
                new ItemFacts("minecraft:lapis_lazuli", 1, Map.of(), Map.of(), ""), true, 30, false);
        assertEquals(EnchantedResultCheck.Verdict.PENDING,
                EnchantedResultCheck.judgeWithCost(levelLate, SWORD, offer, 1, 27));
    }
}
