// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.Objects;
import java.util.Optional;

import it.unimi.dsi.fastutil.longs.LongSets;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.travel.TravelDestination;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 走到没加载的那一片的生产实现：交给走到朝那里走，到附近就够了，那一片早在这之前就加载出来了。
 * 蓝图的格高度是核实过的（作者写的），按确认过的目的地走。
 */
public final class LiveTravels implements ConstructionSeams.Travels {

    /** 到达容差：走到附近就够了。 */
    private static final double NEAR_ENOUGH = 8;

    private final WalkTo walks;

    public LiveTravels(WalkTo walks) {
        this.walks = Objects.requireNonNull(walks, "walks");
    }

    @Override
    public Optional<Action> toward(WorldPosition where, Permissions permissions) {
        TravelDestination destination = TravelDestination.confirmed(where, NEAR_ENOUGH);
        return Optional.of(walks.start(new GoalCompiler.Compiled(destination.navGoal(), LongSets.emptySet()), TerrainPermit.of(permissions)));
    }
}
