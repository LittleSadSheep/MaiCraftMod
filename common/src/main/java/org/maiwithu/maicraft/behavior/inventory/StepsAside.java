// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.Optional;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 走开几步的执行接缝：原地丢完东西后离开拾取范围，免得拾取冷却一过又被自己吸回来。
 *
 * <p>往哪个方向走由走的人自己挑（面前开阔的地方），这里只回答"能不能走开"；
 * 接在出行轨的走到上。走不出去时任务在结果里写明"可能被自己捡回"。
 */
public interface StepsAside {

    /**
     * 从丢东西的位置走开几步的动作；接缝没接上或四面都不通时为 empty。
     *
     * @param awayX 刚丢出时往哪个方向的反方向走（水平分量 x）：人还站在丢出点上时按它起步，不朝东西落的地方走
     * @param awayZ 同上，z 分量
     */
    Optional<Action> stepAway(WorldPosition from, int blocks, double awayX, double awayZ);
}
