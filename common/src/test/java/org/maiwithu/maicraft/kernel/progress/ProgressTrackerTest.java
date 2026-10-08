// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.progress;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** 进度跟踪：有真实进展就继续，长时间没进展才算卡住；没被推进的时间（被打断、暂停）不计。 */
class ProgressTrackerTest {

    @Test
    void stuckOnlyAfterLongEnoughWithoutProgress() {
        ProgressTracker tracker = new ProgressTracker(3, 100);
        tracker.tick();
        tracker.tick();
        assertEquals(ProgressTracker.Status.PROGRESSING, tracker.status());
        tracker.recordProgress("离床更近了");
        tracker.tick();
        tracker.tick();
        assertEquals(ProgressTracker.Status.PROGRESSING, tracker.status());
        tracker.tick();

        var stuck = assertInstanceOf(ProgressTracker.Status.Stuck.class, tracker.status());
        assertEquals("离床更近了", stuck.lastProgress());
        assertEquals(3, stuck.idleTicks());
    }

    @Test
    void interruptedTimeDoesNotCount() {
        // 被自救打断的这段时间里任务不会被推进，也就不会调用 tick()，自然不算卡住。
        ProgressTracker tracker = new ProgressTracker(3, 100);
        tracker.tick();
        tracker.tick();
        assertEquals(ProgressTracker.Status.PROGRESSING, tracker.status());
    }

    @Test
    void stopsAtMaxTicks() {
        ProgressTracker tracker = new ProgressTracker(1000, 2);
        tracker.tick();
        tracker.recordProgress("还在前进");
        tracker.tick();

        assertInstanceOf(ProgressTracker.Status.TimedOut.class, tracker.status());
    }
}
