// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检查门边轻微来回抖动不算前进，真正位移或已确认的操作才更新进度；旧记录到期后不再续时。
 */
class NavigationProgressTest {

    @Test
    void doorwayJitterDoesNotRenewProgress() {
        NavigationProgress progress = new NavigationProgress();
        progress.observe(0.99, 64, 0, 0);
        assertFalse(progress.recent(0, 20), "initial sampling is not progress");
        for (int tick = 1; tick <= 200; tick++) {
            progress.observe(tick % 2 == 0 ? 0.99 : 1.01, 64, 0, tick);
        }
        assertFalse(progress.recent(200, 20), "doorway block-boundary jitter must not renew a lease");
        assertEquals(200, progress.stalledTicks(200), "stalled ticks keep counting through the jitter");
    }

    @Test
    void displacementConfirmsAndThenExpires() {
        NavigationProgress progress = new NavigationProgress();
        progress.observe(0.99, 64, 0, 0);
        for (int tick = 1; tick <= 200; tick++) {
            progress.observe(tick % 2 == 0 ? 0.99 : 1.01, 64, 0, tick);
        }
        progress.observe(1.30, 64, 0, 201);
        assertTrue(progress.recent(201, 0), "physical displacement must count");
        assertFalse(progress.recent(222, 20), "progress must expire without new evidence");
    }

    @Test
    void confirmedNativeChangesCountAndClockRollbackCannotInventEvidence() {
        NavigationProgress progress = new NavigationProgress();
        progress.confirm(230);
        assertTrue(progress.recent(231, 2), "confirmed native changes must count");
        assertFalse(progress.recent(229, 20), "clock rollback cannot invent recent evidence");
    }
}
