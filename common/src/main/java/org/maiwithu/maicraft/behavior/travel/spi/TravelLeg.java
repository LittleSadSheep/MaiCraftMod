// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.spi;

import java.util.List;

import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 用一种方式跑的一段行程：逐刻推进，站稳在这段终点算做完；做不下去以问题失败，人好好地站在某处时
 * 行程会从当前位置接着规划，不判整趟失败。身体只有一套按键：这段结束、取消、人工接管时先松开按下的键再交出。
 */
public interface TravelLeg extends Action {

    /** 先到安全的地方再停：飞着的落地，电梯里的等停靠出轿厢，车上的等停车下车。之后照常推进到停稳。 */
    void requestSafeStop();

    /** 这段里已经确认的变化（换上的装备、坐过的车）。 */
    default List<Change> changes() {
        return List.of();
    }

    /** 这段里说不清的那一笔（例如飞行中断后背包开关状态不明）。 */
    default List<Change> unconfirmed() {
        return List.of();
    }

    /** 一句话说这段怎么样了：坐的哪台电梯、上下哪层，坐的哪趟车、上下哪站。 */
    default String summary() {
        return describe();
    }
}
