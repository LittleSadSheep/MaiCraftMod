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
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 走向站位：按任务许可上路；被打断后走到报"已停下"不算到了，从原地重新上路去同一格；
 * 悬空时能不能打断、先到安全处再停都按走路说，落地后原地等着。
 */
class LiveSpotWalksTest {

    /** 走到替身：被暂停后下一刻报"已停下"，否则一直在路上；悬空时叫它先停稳，再推进两刻落地报"已停下"。 */
    private static final class FakeRun implements WalkRun {
        boolean paused;
        boolean closed;
        boolean airborne;
        /** 叫停之后推进了几刻；没在收尾为 -1。 */
        int stopTicks = -1;

        boolean landed() {
            return stopTicks >= 2;
        }

        @Override public WalkReport report() {
            return paused || landed() ? WalkReport.stopped(BlockPos.ZERO, false) : WalkReport.onTheWay(BlockPos.ZERO, false);
        }

        @Override public boolean stop() { return true; }
        @Override public ActionStatus tick(TickContext context) {
            if (stopTicks >= 0) stopTicks++;
            return paused || landed() ? ActionStatus.done() : ActionStatus.running();
        }
        @Override public boolean stopSafely() {
            if (!airborne) return false;
            stopTicks = 0;
            return true;
        }
        @Override public boolean stoppedSafely() { return stopTicks < 0 || landed(); }
        @Override public Interruptibility interruptibility() {
            return airborne && !landed() ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
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

    @Test
    void 悬空时先停稳转给走路_落地后原地等_暂停过后从原地重新上路() {
        List<FakeRun> runs = new ArrayList<>();
        WalkTo walks = (target, permit) -> {
            FakeRun run = new FakeRun();
            run.airborne = true;
            runs.add(run);
            return run;
        };
        LiveSpotWalks spot = new LiveSpotWalks(walks, TerrainPermit.WALK_ONLY);
        spot.begin(new BlockPos(3, 64, 3));
        assertTrue(spot.tick(null) instanceof ActionStatus.Running);
        assertEquals(Interruptibility.UNSAFE_TO_STOP, spot.interruptibility(), "悬空走着停不得");

        assertTrue(spot.stopSafely(), "悬空走着，要先落地");
        assertFalse(spot.stoppedSafely(), "还没落地");
        for (int i = 0; i < 4; i++) {
            assertTrue(spot.tick(null) instanceof ActionStatus.Running, "落地报的已停下不是到了");
        }
        assertTrue(spot.stoppedSafely(), "落地站稳了");
        assertEquals(1, runs.size(), "收尾途中原地等着，不重新上路");

        spot.pause();
        assertTrue(spot.tick(null) instanceof ActionStatus.Running);
        assertEquals(2, runs.size(), "暂停过后从原地重新上路去同一格");
    }
}
