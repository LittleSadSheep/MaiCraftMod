// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.execute.NavigationStep;

/** Measures useful movement along directed route edges, including returns over familiar ground. */
final class BuildWorksiteProgress {
    private static final int STAGNANT_TICKS = 200;
    private static final int MAX_EDGES = 4096;
    private static final double PROGRESS_DISTANCE = .25;
    private final Map<NavigationStep, Double> closest = new HashMap<>();
    private final Set<BlockPos> changed = new HashSet<>();
    private NavigationStep previous;
    private double closestGoal = Double.NaN;
    private long tick = Long.MIN_VALUE;
    private int stagnant;
    private int drivenSamples, waitingSamples;

    void reset() { closest.clear(); changed.clear(); previous=null; closestGoal=Double.NaN; tick=Long.MIN_VALUE; stagnant=0; }
    void changed(BlockPos pos) {
        if (changed.size()<MAX_EDGES && changed.add(pos.immutable())) { stagnant=0;closest.clear();previous=null; }
    }
    boolean observe(Vec3 feet, Vec3 goal, long revision, NavigationStep step) {
        if (revision == tick) return false;
        tick = revision;
        if (step == null) { waitingSamples++;return false; }
        drivenSamples++;
        double distance=feet.distanceTo(goal);
        boolean progressed=Double.isFinite(closestGoal) && closestGoal-distance>=PROGRESS_DISTANCE;
        if (!Double.isFinite(closestGoal) || progressed) closestGoal=distance;
        // The executor may already have advanced to the next edge when we observe its last movement.
        if (previous != null) progressed |= approaching(previous, feet);
        progressed |= approaching(step, feet);previous=step;
        if (progressed) stagnant=0;else stagnant++;
        return stagnant >= STAGNANT_TICKS;
    }
    private boolean approaching(NavigationStep step, Vec3 feet) {
        double distance=feet.distanceTo(Vec3.atBottomCenterOf(step.to()));
        Double best=closest.get(step);
        if (best == null) { if (closest.size()<MAX_EDGES) closest.put(step,distance);return false; }
        if (best-distance<PROGRESS_DISTANCE) return false;
        closest.put(step,distance);return true;
    }
    int stagnantTicks() { return stagnant; }
    Map<String,Integer> evidence() { return Map.of("driven_samples",drivenSamples,"waiting_samples",waitingSamples,"stagnant_ticks",stagnant); }
}
