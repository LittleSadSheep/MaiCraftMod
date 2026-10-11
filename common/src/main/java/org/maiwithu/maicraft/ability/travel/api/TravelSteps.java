// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.travel.api;

import java.util.Map;

import org.maiwithu.maicraft.ability.travel.JourneyInput;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.travel.JourneyTask;
import org.maiwithu.maicraft.behavior.travel.TravelDestination;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 别的能力要先去某处时交给内核的一步：和 travel 不给 via 时一样——近处走、远处比报价，
 * 目的地在船上时上船那一段由出行方式接手。别的能力用 {@code StepDecision.Run(..., true)} 跑它，结束后回来接着决定。
 */
public final class TravelSteps {

    private TravelSteps() {}

    /**
     * 去一个高度核实过的地方。
     *
     * @param where       去哪
     * @param radius      到达容差（格）
     * @param permissions 这次的许可：走路的几段能动多少地形照它
     */
    public static TaskInput journeyTo(WorldPosition where, double radius, Permissions permissions) {
        return new JourneyInput(new JourneyTask.Journey(TravelDestination.confirmed(where, radius), null, Map.of(),
                permissions, 0), TerrainPermit.of(permissions));
    }
}
