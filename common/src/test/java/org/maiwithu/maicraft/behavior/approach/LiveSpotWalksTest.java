// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 走向站位：按任务许可上路；被打断后走到报"已停下"不算到了，从原地重新上路去同一格。 */
class LiveSpotWalksTest {

    /** 走到替身：被暂停后下一刻报"已停下"，否则一直在路上。 */
    private static final class FakeRun implements WalkRun {
        boolean paused;
        boolean closed;

        @Override public WalkReport report() {
            return paused ? WalkReport.stopped(BlockPos.ZERO, false) : WalkReport.onTheWay(BlockPos.ZERO, false);
        }

        @Override public boolean stop() { return true; }
        @Override public ActionStatus tick(TickContext context) {
            return paused ? ActionStatus.done() : ActionStatus.running();
        }
        @Override public void pause() { paused = true; }
        @Override public void close() { closed = true; }
        @Override public String describe() { return "走着"; }
    }

    @Test
    void 被打断后重新上路去同一格() {
        List<FakeRun> runs = new ArrayList<>();
        List<TerrainPermit> permits = new ArrayList<>();
        WalkTo walks = (target, permit) -> {
            FakeRun run = new FakeRun();
            runs.add(run);
            permits.add(permit);
            return run;
        };
        LiveSpotWalks spot = new LiveSpotWalks(walks, TerrainPermit.WALK_ONLY);
        spot.begin(new BlockPos(3, 64, 3));
        assertTrue(spot.tick(null) instanceof ActionStatus.Running);
        spot.pause();
        assertTrue(spot.tick(null) instanceof ActionStatus.Running, "停下不是到了");
        assertEquals(2, runs.size());
        assertTrue(runs.getFirst().closed);
        assertEquals(List.of(TerrainPermit.WALK_ONLY, TerrainPermit.WALK_ONLY), permits);
    }
}
