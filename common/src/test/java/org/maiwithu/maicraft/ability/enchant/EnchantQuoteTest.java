// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.enchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * 报价判断的离线场景：三档门槛与实扣分开展示；创造模式门槛与青金石按 0；
 * 档位可用性如实回答；种子、工作槽或等级变了报价指纹就变。
 */
class EnchantQuoteTest {

    /** 一份 20 级玩家的读数：三档门槛 2/10/30，第二档有锋利线索。 */
    private static EnchantMenuFacts.Snapshot snapshot(int level, boolean creative) {
        return new EnchantMenuFacts.Snapshot(
                new int[]{2, 10, 30}, new String[]{"", "minecraft:sharpness", ""},
                new int[]{0, 3, 0}, 42,
                ItemFacts.EMPTY, ItemFacts.EMPTY, true, level, creative);
    }

    @Test
    void 三档门槛与实扣分开写实扣是当前等级与档位取小() {
        EnchantQuote quote = EnchantQuote.freeze(snapshot(20, false));
        assertEquals(2, quote.offer(1).thresholdLevel());
        assertEquals(1, quote.offer(1).levelsSpent(), "第一档只扣一级");
        assertEquals(10, quote.offer(2).thresholdLevel());
        assertEquals(3, quote.offer(3).levelsSpent(), "20 级按第三档只扣三级");
        assertEquals(2, quote.offer(2).lapisCost(), "第 n 档扣 n 个青金石");
    }

    @Test
    void 等级低于档位时实扣归到当前等级() {
        EnchantQuote quote = EnchantQuote.freeze(snapshot(2, false));
        assertEquals(2, quote.offer(3).levelsSpent(), "2 级按第三档只扣两级，扣不到负数");
    }

    @Test
    void 创造模式免青金石免门槛() {
        EnchantQuote quote = EnchantQuote.freeze(snapshot(0, true));
        assertEquals(0, quote.offer(2).lapisCost(), "创造不扣青金石");
        assertTrue(quote.offer(2).usable(true, 0), "创造免门槛免等级，有线索就可选");
        assertFalse(quote.offer(1).usable(true, 0), "没有线索的空档创造也点不了");
    }

    @Test
    void 档位可用性如实回答() {
        EnchantQuote quote = EnchantQuote.freeze(snapshot(20, false));
        assertTrue(quote.offer(2).usable(false, 20), "门槛到了又有线索");
        assertFalse(quote.offer(3).usable(false, 20), "30 级的门槛 20 级过不去");
        assertFalse(quote.offer(1).usable(false, 20), "这一档没有线索是空档");
    }

    @Test
    void 种子工作槽或等级变了报价就对不上() {
        EnchantMenuFacts.Snapshot before = snapshot(20, false);
        EnchantQuote quote = EnchantQuote.freeze(before);
        assertTrue(quote.stillMatches(snapshot(20, false)), "同样的读数没变");
        assertFalse(quote.stillMatches(new EnchantMenuFacts.Snapshot(
                before.costs(), before.clues(), before.clueLevels(), 43,
                before.itemSlot(), before.lapisSlot(), before.cursorEmpty(), before.playerLevel(), false)),
                "种子变了（附过一次魔）报价就变");
        assertFalse(quote.stillMatches(new EnchantMenuFacts.Snapshot(
                before.costs(), before.clues(), before.clueLevels(), before.seed(),
                new ItemFacts("minecraft:iron_sword", 1, Map.of(), Map.of(), "plain"),
                before.lapisSlot(), before.cursorEmpty(), before.playerLevel(), false)),
                "工作槽放了东西指纹就变");
        assertFalse(quote.stillMatches(snapshot(19, false)), "等级变了指纹就变");
    }
}
