package org.maiwithu.maicraft.core.task.mine;

import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;

/** One failed search, retained only while the finite candidate query is still progressing.
 * No terrain revision is available here: a terrain-only change needs a fresh task after this
 * query settles. This record never keeps an unchanged complete query waiting indefinitely. */
public record NoPathVerdict(Vec3 source, GoalCompiler.CompiledFingerprint goals, String detail) {
    public enum Next { SEARCH, WAIT_FOR_QUERY, FAIL }

    public Next next(Vec3 currentSource, GoalCompiler.CompiledFingerprint currentGoals, boolean queryComplete) {
        if (!source.equals(currentSource) || !goals.equals(currentGoals)) return Next.SEARCH;
        return queryComplete ? Next.FAIL : Next.WAIT_FOR_QUERY;
    }
}
