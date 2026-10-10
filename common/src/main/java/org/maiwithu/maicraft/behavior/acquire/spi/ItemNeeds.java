// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 内部需求的入口：来源备料时缺的原料、缺的工具，都回到同一个拿到物品的引擎去弄。
 * 合成要铁锭、烧炼要燃料、挖矿要镐，走的是同一套问来源、挑最省事的路的过程；
 * 递归的深度上限与防环由实现方（拿到物品的引擎）统一把守，来源不用自己数。
 */
public interface ItemNeeds {

    /**
     * 为一次内部需求给出逐刻推进的动作：把缺的东西弄进背包算做完，弄不到以问题失败。
     * 返回的动作还没开始推进，来源把它排进自己的执行顺序里。
     * 许可原样传下去：内部需求与外面的主需求受同一份许可管。
     */
    Action actionFor(ItemRequest request, Permissions permissions);

    /**
     * 任务直接发起的内部需求（用东西缺了要拿的、施工缺料、备床）：带上任务的记账口，
     * 拿东西途中腾地方存了丢了什么、点了没能确认的，都记进这个任务的结果。
     * 来源备料用上面那个，记账口从外层的拿东西接过来。不在乎这些事实的实现可以只实现上面那个。
     */
    default Action actionFor(ItemRequest request, Permissions permissions, TaskRecords records) {
        return actionFor(request, permissions);
    }
}
