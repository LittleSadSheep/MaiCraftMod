// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.List;
import java.util.Set;

import org.maiwithu.maicraft.behavior.combat.BattleMoves;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 战斗的动手与拾荒接缝：打的那部分就是战斗引擎的动手（和自卫同一份），拾荒用玩家行为层共用的捡掉落物
 * （和采集、打猎同一套：只捡这一仗带出来的，进没进包按服务器的拾取包认）。
 *
 * <p>任务负责挑目标、挑时机与结算，动手交给行为模型；生产实现是原生攻击与走到，测试换替身。
 */
interface FightMoves extends BattleMoves {

    /** 掉落物确认此刻的序号：开打前记一份，之后只认这之后冒出来的掉落与本玩家的拾取。 */
    long dropCursor(TickContext context);

    /** 身边此刻躺着、在记下的序号之前就有的掉落物（实体编号）：不是这一仗带出来的，不去碰。 */
    Set<Integer> dropsLyingBefore(TickContext context, long cursor);

    /** 走过去捡起这些之外新冒出来的掉落物（共用的捡掉落物）；捡不到的如实失败。 */
    Action pickUpNewSince(Set<Integer> before);

    /** 记下的序号之后本玩家收进包的、这之后才冒出来的掉落：服务器说收了才算，背包多了不算。 */
    List<Gained> pickedUpSince(TickContext context, long cursor);

    /** 收进包的一样东西：物品 ID 与件数。 */
    record Gained(String item, int count) {}
}
