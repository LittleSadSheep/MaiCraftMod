// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.List;

import org.maiwithu.maicraft.behavior.combat.BattleMoves;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 战斗的动手与拾荒接缝：打的那部分就是战斗引擎的动手（和自卫同一份），外加掉落物与拾取确认。
 *
 * <p>任务负责挑目标、挑时机与结算，动手交给行为模型；生产实现是原生攻击与走到，测试换替身。
 */
interface FightMoves extends BattleMoves {

    /** 掉落点附近的物品实体：就地捡战利品用。 */
    List<Drop> dropsNear(TickContext context, double x, double y, double z, double radius);

    /** 掉落物确认此刻的序号：拾荒前记一份，之后只认这之后本玩家的拾取。 */
    long dropCursor(TickContext context);

    /** 记下的序号之后本玩家从这件掉落物上收了几件：服务器说收了才算，背包多了不算。 */
    int pickedUp(TickContext context, int entityId, long sinceCursor);

    /** 一个待捡的掉落物。 */
    record Drop(int entityId, String item, double x, double y, double z) {}
}
