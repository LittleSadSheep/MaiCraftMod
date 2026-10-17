// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.enchant;

/**
 * 附魔台三档报价里的一档：门槛等级、实扣等级、青金石数与一条附魔线索。
 *
 * <p>门槛随书架与随机种子变化（最高三十级）；消耗固定为档位数——第 n 档扣 n 级 n 个青金石，
 * 实扣等级是当前等级与档位取小、最低归零；创造模式免门槛免料。
 */
record TierOffer(int tier, int thresholdLevel, int levelsSpent, int lapisCost, String clue, int clueLevel) {

    /** 这一档现在选不选得：没有线索的档是空档；门槛没到或等级为零时如实说差多少。 */
    boolean usable(boolean creative, int playerLevel) {
        if (clue.isEmpty() || clueLevel <= 0 || thresholdLevel <= 0) return false;
        return creative || playerLevel >= thresholdLevel;
    }
}
