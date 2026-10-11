// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 狩猎的出手与走位接缝：对一只生物原生攻击一次、朝一处走过去、角色此刻在哪。
 * 攻击的充能与确认都按游戏接口层的攻击交互走（与自卫、战斗同一条攻击事实），测试换替身。
 */
public interface HuntMoves {

    /** 对这只生物出手一次（原生攻击，充能够格才挥、逐刻确认）。 */
    Action strike(TickContext context, int entityId);

    /** 朝一个位置走过去（只走不改）。 */
    Action walkTo(double x, double y, double z);

    /** 此刻角色所在的位置。 */
    double[] selfPosition(TickContext context);
}
