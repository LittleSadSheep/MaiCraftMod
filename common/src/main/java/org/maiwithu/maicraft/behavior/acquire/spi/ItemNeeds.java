// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
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

    /**
     * 这一次拿东西里是不是已经确认拿不到它：问遍了各来源、连自己做都做不成。
     * 来源排备料先后时用：先去碰已知拿不到的那样，一件都没有就当场放弃这个做法，不先跑去弄别的原料。
     * 不在拿东西的过程中、或实现不记这件事时为 false。
     */
    default boolean knownUnobtainable(WantedItem wanted) {
        return false;
    }

    /**
     * 问价不拿：问遍各来源，把这点东西弄进背包最近的一条路要走多远（格，按来源自报的代价里的距离算）。
     * 来源排几种做法的先后时用：缺得一样多的做法，缺的原料近的先试，不为凑一块板跑去十格外。
     * 各来源都给不了、或还有来源没看清现场时为空——问不出远近，排法退回原来的先后。
     */
    default Optional<Double> nearestDistanceBlocks(ItemRequest request, Permissions permissions) {
        return Optional.empty();
    }

    /**
     * 先看一眼：问各来源有没有"打开看一眼才知道"的（身上的 AE 终端这五分钟没开过），给第一个看一眼的动作；
     * 都不用看给空。几种做法分不出先后时，看完再挑。
     */
    default Optional<Action> lookAround(ItemRequest request, Permissions permissions) {
        return Optional.empty();
    }
}
