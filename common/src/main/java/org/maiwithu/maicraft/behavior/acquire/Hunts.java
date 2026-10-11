// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.List;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 狩猎的执行接缝：走近、看准、交给战斗域的动作出手，打倒了就地收掉落。
 * 目标物品够数为止；打了一只没见到要的东西，记下原因换下一只。测试用替身。
 */
public interface Hunts {

    /** 为一次狩猎生成动作：按给的顺序逐只试（扫描已按由近及远排好）。 */
    Optional<Action> hunt(List<ScansCreatures.CreatureSpot> candidates, ItemRequest request,
            Permissions permissions);

    /**
     * 附近没有候选、愿意走时出去找的动作：走到一片看一片，见到候选就地转入狩猎，
     * 走满给定的距离还没见到就如实失败，不把身体带进没有上限的搜索。
     */
    Optional<Action> searchAndHunt(SearchPlan plan, Permissions permissions);

    /** 出去找一片的打算：找哪些类型、羊毛要什么颜色、扫多大、走多远、多久看一片。 */
    record SearchPlan(List<String> typeIds, Optional<String> sheepColorNeeded, boolean woolRequest,
            int scanRadiusBlocks, double maxDistanceBlocks, int stepBlocks,
            ScansCreatures scans, ReadsCreatureSituation creatures, PermissionCheck permission) {
    }
}
