// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.progress;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** 进度表：有真实进展就继续，停滞超窗才算卡住；没被推进的时间（被抢占、暂停）不计。 */
class ProgressMeterTest {

    @Test
    void stallsOnlyAfterWindowWithoutProgress() {
        ProgressMeter meter = new ProgressMeter(3, 100);
        meter.tick();
        meter.tick();
        assertEquals(ProgressMeter.Verdict.MOVING, meter.verdict());
        meter.advanced("离床更近了");
        meter.tick();
        meter.tick();
        assertEquals(ProgressMeter.Verdict.MOVING, meter.verdict());
        meter.tick();

        var stalled = assertInstanceOf(ProgressMeter.Verdict.Stalled.class, meter.verdict());
        assertEquals("离床更近了", stalled.lastSignal());
        assertEquals(3, stalled.idleTicks());
    }

    @Test
    void preemptedTimeDoesNotCount() {
        // 被自救反射抢占的这段时间里执行器不会被推进，也就不会调用 tick()，自然不算停滞。
        ProgressMeter meter = new ProgressMeter(3, 100);
        meter.tick();
        meter.tick();
        assertEquals(ProgressMeter.Verdict.MOVING, meter.verdict());
    }

    @Test
    void stopsAtHardCap() {
        ProgressMeter meter = new ProgressMeter(1000, 2);
        meter.tick();
        meter.advanced("还在前进");
        meter.tick();

        assertInstanceOf(ProgressMeter.Verdict.OverCap.class, meter.verdict());
    }
}
