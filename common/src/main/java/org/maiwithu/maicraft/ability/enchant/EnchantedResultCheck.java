// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.enchant;

import java.util.Map;

/**
 * 成品判据：一次附魔算不算真的做成了——成品、费用、线索三方面同刻吻合的判断。
 *
 * <p>原版的事实：书附成附魔书（附魔进储存附魔组件）；装备只允许附魔组件变化，
 * 损伤、名字等组件原样保留；报价线索那条附魔必须真的在成品里。
 * 费用（青金石与等级的扣减）由任务按同刻读数核对，这里只判成品与线索。
 */
final class EnchantedResultCheck {

    /** 判定结论：做成了、还没成（继续等分包抵达）、或变成了预料之外。 */
    enum Verdict { CONFIRMED, PENDING, DIVERGED }

    private EnchantedResultCheck() {}

    /**
     * 判工作槽第 0 格现在的样子算不算这件东西附成了。
     *
     * @param original  装进去时的原物（未附魔的那一件）
     * @param slotNow   工作槽第 0 格此刻的样子
     * @param clue      冻结报价里这一档的线索附魔（注册 ID）；没有线索的档无从对证
     */
    static Verdict judge(ItemFacts original, ItemFacts slotNow, String clue, int clueLevel) {
        if (slotNow.isEmpty()) return Verdict.PENDING;
        // 书附成附魔书：附魔写进储存附魔组件，物品本身换了。
        if (original.itemId().equals("minecraft:book")) {
            if (!slotNow.itemId().equals("minecraft:enchanted_book")) return Verdict.DIVERGED;
            return cluePresent(slotNow.storedEnchantments(), clue, clueLevel) ? Verdict.CONFIRMED : Verdict.PENDING;
        }
        // 装备与工具：物品不变，只有附魔组件变化，其余组件（损伤、名字等）原样保留。
        if (!slotNow.itemId().equals(original.itemId())) return Verdict.DIVERGED;
        if (!slotNow.otherComponents().equals(original.otherComponents())) return Verdict.DIVERGED;
        return cluePresent(slotNow.enchantments(), clue, clueLevel) ? Verdict.CONFIRMED : Verdict.PENDING;
    }

    /** 线索那条附魔必须真的在成品里，等级也要对得上；不在就继续等，等级不一致说明按错了档。 */
    private static boolean cluePresent(Map<String, Integer> enchantments, String clue, int clueLevel) {
        return clue != null && !clue.isEmpty() && enchantments.getOrDefault(clue, -1) == clueLevel;
    }

    /**
     * 成品、费用三方面同刻吻合的确认：成品判据过了，青金石少了对的数、等级扣了对的数才算数。
     * 成品与费用分包抵达就继续等（PENDING），变成预料之外才算不一致。
     *
     * @param lapisAfter 按钮按下前工作槽的青金石数减去档位数
     * @param levelAfter 按钮按下前的玩家等级减去实扣等级
     */
    static Verdict judgeWithCost(EnchantMenuFacts.Snapshot now, ItemFacts original, TierOffer offer,
                                 int lapisAfter, int levelAfter) {
        var outcome = judge(original, now.itemSlot(), offer.clue(), offer.clueLevel());
        if (outcome == Verdict.DIVERGED) return Verdict.DIVERGED;
        int lapisNow = now.lapisSlot().itemId().equals("minecraft:lapis_lazuli") ? now.lapisSlot().count() : 0;
        if (outcome == Verdict.CONFIRMED && lapisNow == lapisAfter && now.playerLevel() == levelAfter) {
            return Verdict.CONFIRMED;
        }
        return Verdict.PENDING;
    }
}
