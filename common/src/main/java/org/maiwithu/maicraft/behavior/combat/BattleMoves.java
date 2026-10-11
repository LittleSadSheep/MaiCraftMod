// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 战斗引擎动手的接缝：手上出手、拉弓、举盾，脚下迈步与寻路，以及看自己在哪、脚下能不能站。
 * 生产实现是原生攻击、按键走位与走到，离线测试换替身记录调用。
 */
public interface BattleMoves {

    /** 对这只出手一次：先把挑好的近战武器拿到主手，再原生攻击（充能够格才挥、无敌帧不挥、逐刻确认）。 */
    Action strike(TickContext context, int entityId);

    /** 冲这只射一箭（或用联动远程打一发）：拿弓弩、拉弓或装弩、对准、放。 */
    Action shoot(TickContext context, int entityId);

    /** 身上有没有能射的。 */
    boolean canShoot(TickContext context);

    /** 身上有没有剑斧这类近战武器。 */
    boolean hasMeleeWeapon(TickContext context);

    /** 攻击此刻充能好了没。 */
    boolean attackReady(TickContext context);

    /** 举盾放盾：对手离这么近、这一刻是不是正要出手（要出手就放下）。 */
    void guard(TickContext context, double distance, boolean aboutToStrike);

    /** 不打了：盾放下。 */
    void lowerGuard();

    /** 当刻脚下的路：走位挑方向用。 */
    Footwork.Ground ground(TickContext context);

    /** 按挑好的一步迈这一刻；faceEntityId 为 -1 时朝走的方向。 */
    void step(TickContext context, int faceEntityId, Footwork.Step step);

    /** 交给寻路走一段（只走不改地形）。 */
    Action walk(GoalCompiler.Compiled goal);

    /** 此刻角色脚下的位置 {x, y, z}。 */
    double[] selfPosition(TickContext context);

    /** 这一格能不能站（已加载、脚下踩得实、身子两格空、没有水）：撤离挑落点用。 */
    boolean standable(TickContext context, int x, int y, int z);

    /** 此刻主手里拿着什么（物品 ID，空手为"空手"）。 */
    String mainHand(TickContext context);
}
