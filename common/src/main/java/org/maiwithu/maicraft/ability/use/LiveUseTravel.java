// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

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
 * 走到没加载的坐标的生产实现：交给走到朝那里走，目的地按出行的高度纪律编译——
 * 高度没核实只走到那一柱列，不把没核实的高度交给寻路。任务看到那一片加载出来就停下回去找目标，
 * 所以这里只需要"往那边走"，到达容差放得宽。
 */
final class LiveUseTravel implements UseSeams.TravelsTo {

    /** 到达容差：走到附近就够了，那一片早在这之前就加载出来了。 */
    private static final double NEAR_ENOUGH = 8;

    private final WalkTo walks;

    LiveUseTravel(WalkTo walks) {
        this.walks = Objects.requireNonNull(walks, "walks");
    }

    @Override
    public Optional<Action> toward(WorldPosition where, boolean heightKnown, Permissions permissions) {
        TravelDestination destination = heightKnown
                ? TravelDestination.confirmed(where, NEAR_ENOUGH)
                : TravelDestination.anyHeight(where.x(), where.z(), NEAR_ENOUGH, where.dimension());
        return Optional.of(walks.start(new GoalCompiler.Compiled(destination.navGoal(), LongSets.emptySet()),
                TerrainPermit.of(permissions)));
    }
}
