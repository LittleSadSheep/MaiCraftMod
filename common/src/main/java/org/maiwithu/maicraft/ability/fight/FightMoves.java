// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.List;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 战斗的动手与拾荒接缝：出手、走位、掉落物与背包计数。
 *
 * <p>任务负责挑目标、挑时机与结算，动手交给行为模型；生产实现是原生攻击与走到，测试换替身。
 */
interface FightMoves {

    /** 对这只生物出手一次（原生攻击，充能够格才挥、逐刻确认）。 */
    Action strike(TickContext context, int entityId);

    /** 朝一个位置走过去（只走不改）。 */
    Action walkTo(double x, double y, double z);

    /** 此刻角色所在的位置。 */
    double[] selfPosition(TickContext context);

    /** 掉落点附近的物品实体：就地捡战利品用。 */
    List<Drop> dropsNear(TickContext context, double x, double y, double z, double radius);

    /** 随身物品总件数：拾荒前后对比，确认东西真的进了包。 */
    int carriedItemCount(TickContext context);

    /** 一个待捡的掉落物。 */
    record Drop(int entityId, String item, double x, double y, double z) {}
}
