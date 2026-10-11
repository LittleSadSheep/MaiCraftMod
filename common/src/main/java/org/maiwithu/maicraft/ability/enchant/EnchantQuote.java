// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.enchant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 冻结的附魔报价：读数稳定后抄下的三档与当时的现场（等级、工作槽、随机种子）。
 *
 * <p>一次附魔后随机种子改变、报价会变；提交前拿冻结时的指纹再核一遍现场，
 * 对不上就重读再等稳定，绝不按过期的报价按按钮。
 */
final class EnchantQuote {

    private final List<TierOffer> offers;
    private final int playerLevel;
    private final int lapisCount;
    private final int fingerprint;

    private EnchantQuote(List<TierOffer> offers, int playerLevel, int lapisCount, int fingerprint) {
        this.offers = List.copyOf(offers);
        this.playerLevel = playerLevel;
        this.lapisCount = lapisCount;
        this.fingerprint = fingerprint;
    }

    /** 从一次读数冻结报价：三档各写门槛、实扣等级（当前等级与档位取小、最低归零）、青金石数与线索。 */
    static EnchantQuote freeze(EnchantMenuFacts.Snapshot snapshot) {
        List<TierOffer> offers = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            int tier = i + 1;
            offers.add(new TierOffer(tier, snapshot.costs()[i],
                    Math.min(Math.max(snapshot.playerLevel(), 0), tier),
                    snapshot.creative() ? 0 : tier,
                    snapshot.clues()[i], snapshot.clueLevels()[i]));
        }
        return new EnchantQuote(offers, snapshot.playerLevel(),
                lapisIn(snapshot), fingerprintOf(snapshot));
    }

    /** 冻结的三档报价。 */
    List<TierOffer> offers() {
        return offers;
    }

    /** 冻结时的玩家等级；选档不可用时如实写差多少。 */
    int playerLevel() {
        return playerLevel;
    }

    int lapisCount() {
        return lapisCount;
    }

    /** 提交前核对：等级、随机种子、三档读数、工作槽都对得上才算报价没变。 */
    boolean stillMatches(EnchantMenuFacts.Snapshot snapshot) {
        return fingerprint == fingerprintOf(snapshot);
    }

    /** 第 tier 档（1..3）。 */
    TierOffer offer(int tier) {
        return offers.get(tier - 1);
    }

    /** 报价指纹：三档读数、种子、工作槽与等级任何一样变了，指纹就变。 */
    private static int fingerprintOf(EnchantMenuFacts.Snapshot snapshot) {
        return Objects.hash(Arrays.hashCode(snapshot.costs()), Arrays.hashCode(snapshot.clues()),
                Arrays.hashCode(snapshot.clueLevels()), snapshot.seed(), snapshot.itemSlot(),
                snapshot.lapisSlot(), snapshot.playerLevel());
    }

    /** 工作槽里现在的青金石数。 */
    private static int lapisIn(EnchantMenuFacts.Snapshot snapshot) {
        return snapshot.lapisSlot().itemId().equals("minecraft:lapis_lazuli") ? snapshot.lapisSlot().count() : 0;
    }
}
