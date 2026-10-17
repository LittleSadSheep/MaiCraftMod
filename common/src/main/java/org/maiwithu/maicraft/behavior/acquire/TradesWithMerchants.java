// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 交易的执行接缝：逐个走到村民跟前，点开交易界面，按要的量一连买，买够或都试完为止。
 * 界面上的原生动作用游戏接口层的菜单入口（点选报价、点格子、关界面），测试用替身。
 * 开不出界面、界面里有别人的东西、付款补不到，都以问题失败并说清是哪种。
 */
public interface TradesWithMerchants {

    /**
     * 为一次买卖生成动作：按给的顺序逐个候选试，买到请求的数量为止。
     * 做完时身上想要的东西变多了多少，由引擎重新清点；一个都买不到时以问题失败，原因里写清缺付款、
     * 没货还是没有开得出界面的村民。付款不够时按同一套拿东西的办法先去凑，许可原样传下去。
     */
    Optional<Action> trade(Iterable<ScansMerchants.MerchantSpot> candidates, ItemRequest request,
            Permissions permissions);
}
