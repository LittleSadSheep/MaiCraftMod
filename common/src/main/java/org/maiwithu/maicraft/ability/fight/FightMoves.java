// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.List;

import org.maiwithu.maicraft.behavior.survival.CloseCombat;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 战斗的动手与拾荒接缝：出手、走位、掉落物与背包计数。
 *
 * <p>任务负责挑目标、挑时机与结算，动手交给行为模型；生产实现是原生攻击与走到，测试换替身。
 */
interface FightMoves extends CloseCombat.Moves {

    /** 对这只生物出手一次：先把挑好的近战武器拿到主手，再原生攻击（充能够格才挥、逐刻确认）。 */
    @Override
    Action strike(TickContext context, int entityId);

    /** 此刻主手里拿着什么（物品 ID，空手为"空手"）：结果里写真正用来打的那件。 */
    String mainHand(TickContext context);

    /** 朝一个位置走过去（只走不改）。 */
    Action walkTo(double x, double y, double z);

    /** 此刻角色所在的位置。 */
    double[] selfPosition(TickContext context);

    /** 掉落点附近的物品实体：就地捡战利品用。 */
    List<Drop> dropsNear(TickContext context, double x, double y, double z, double radius);

    /** 掉落物确认此刻的序号：拾荒前记一份，之后只认这之后本玩家的拾取。 */
    long dropCursor(TickContext context);

    /** 记下的序号之后本玩家从这件掉落物上收了几件：服务器说收了才算，背包多了不算。 */
    int pickedUp(TickContext context, int entityId, long sinceCursor);

    /** 一个待捡的掉落物。 */
    record Drop(int entityId, String item, double x, double y, double z) {}
}
