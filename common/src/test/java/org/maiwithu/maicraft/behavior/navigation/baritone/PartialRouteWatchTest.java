// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 半截路看有没有更近：一直在挪近的不放弃，来回挪了半分钟没更近两格的放弃，有了整路从头算。
 */
class PartialRouteWatchTest {

    @Test
    void keepsGoingWhileGettingCloser() {
        PartialRouteWatch watch = new PartialRouteWatch();
        double remaining = 400;
        for (long tick = 0; tick <= 2000; tick += 20) {
            // 每秒近一格多。
            assertFalse(watch.stalled(remaining, tick));
            remaining -= 5;
        }
    }

    @Test
    void givesUpAfterHalfAMinuteOfPacing() {
        PartialRouteWatch watch = new PartialRouteWatch();
        boolean stalled = false;
        for (long tick = 0; tick <= PartialRouteWatch.LIMIT_TICKS + 40; tick += 20) {
            // 来回挪：估价在 100 上下晃一格。
            stalled = watch.stalled(tick % 40 == 0 ? 100 : 103, tick);
        }
        assertTrue(stalled);
    }

    @Test
    void fullRouteStartsOver() {
        PartialRouteWatch watch = new PartialRouteWatch();
        watch.stalled(100, 0);
        watch.reset();
        assertFalse(watch.stalled(100, PartialRouteWatch.LIMIT_TICKS + 100));
    }
}
