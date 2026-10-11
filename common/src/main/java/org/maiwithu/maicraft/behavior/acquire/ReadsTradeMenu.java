// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 交易界面的只读读端：现在点开的界面里同步过来的报价与三格现状。
 * 点开的不是交易界面、报价还没同步完时读不到，调用方按没读到来等，不把没同步当成空列表。
 */
public interface ReadsTradeMenu {

    /** 现在开着的交易界面的报价列表；没开着交易界面或还没同步完时给空。 */
    Optional<List<TradeOffers.View>> offers();

    /** 付款格（第 0、1 格）与结果格（第 2 格）现在的样子；没开着交易界面时给空。 */
    Optional<TradeOffers.SlotContent> slot(int index);

    /**
     * 背包里哪一格放得下这一份货：结果格里的货（光标空着时）或光标上拿着的货（刚从结果格拿起来时），
     * 先找同样东西还装得下的一堆，再找空格；返回交易界面里的格号，哪儿都放不下给空。
     */
    OptionalInt stashSlot();

    /** 结果格现在装着的是不是想要的东西：付款照报价给，只有结果格要跟想要的对。 */
    default Optional<Boolean> resultMatches(ItemRequest request, ReadsItemTags tags) {
        return slot(2).map(content -> !content.isEmpty()
                && request.wanted().matches(content.itemId(), tags.tagsOf(content.itemId())));
    }
}
