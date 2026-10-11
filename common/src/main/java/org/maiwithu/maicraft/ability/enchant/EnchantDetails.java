// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.enchant;

import java.util.List;

import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 附魔的结果细节：冻结的三档报价、这次用的档位、实际附上的条目与实扣的费用。
 *
 * @param offers      冻结时的三档报价：门槛等级、实扣等级、青金石数、附魔线索
 * @param tier        这次用的档位
 * @param enchantments 实际附上的条目与等级；只有按钮确认后才列，报价线索不冒充结果
 * @param levelsSpent 实扣掉的等级
 * @param lapisSpent  实扣掉的青金石
 */
record EnchantDetails(List<TierOffer> offers, int tier, List<String> enchantments,
                      int levelsSpent, int lapisSpent) implements ResultDetails {

    EnchantDetails {
        offers = offers == null ? List.of() : List.copyOf(offers);
        enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
    }
}
