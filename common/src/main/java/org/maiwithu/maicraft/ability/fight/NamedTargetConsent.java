// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.Optional;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 点名的目标能不能打：点名本身就是许可，但有主的生物（有名字、驯服、拴绳、圈养）和玩家
 * 点了名也要再确认一次——这次任务的 fight 开到 any 才动手。不能打时给出要上报的问题。
 */
interface NamedTargetConsent {

    /** 这只点名的目标能不能按这次任务的许可开打；能打为空。 */
    Optional<Problem> check(SeenTargets.Locked target, Permissions permissions);
}
