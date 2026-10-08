// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 默认纯行走也会经过调度健康检查；复现请求消失、暂停与实际行走后恢复预算的不同情况。
 */
class NavigationDispatchWatchdogTest {

    @Test
    void waitingForConfirmationOrHumanControlDoesNotReset() {
        var watchdog = new NavigationDispatchWatchdog();
        long tick = 0;
        for (int i = 0; i < 200; i++) {
            assertEquals(NavigationDispatchWatchdog.Action.NONE,
                    watchdog.observe(++tick, false, false, false),
                    "waiting for native confirmation or human control must not trigger a reset");
        }
    }

    @Test
    void lostRequestIsRestartedOnceThenFailsExplicitly() {
        var watchdog = new NavigationDispatchWatchdog();
        long tick = 0;
        for (int i = 0; i < 39; i++) {
            assertEquals(NavigationDispatchWatchdog.Action.NONE,
                    watchdog.observe(++tick, true, false, false),
                    "normal first-frame dispatch receives a bounded grace period");
        }
        assertEquals(NavigationDispatchWatchdog.Action.RESTART,
                watchdog.observe(++tick, true, false, false), "lost request is restarted once");
        assertEquals(NavigationDispatchWatchdog.Action.NONE,
                watchdog.observe(tick, true, false, false),
                "repeated observation in one tick does not consume another tick");
        for (int i = 0; i < 39; i++) watchdog.observe(++tick, true, false, false);
        assertEquals(NavigationDispatchWatchdog.Action.FAIL,
                watchdog.observe(++tick, true, false, false),
                "repeated zero-search state fails explicitly rather than retrying forever");
    }

    @Test
    void activeSearchOrKnownOutcomeIsNotALostDispatch() {
        var watchdog = new NavigationDispatchWatchdog();
        long tick = 0;
        for (int i = 0; i < 100; i++) {
            assertEquals(NavigationDispatchWatchdog.Action.NONE,
                    watchdog.observe(++tick, true, true, false),
                    "active search, existing movement or confirmed no-solution is not a lost dispatch");
        }
    }

    @Test
    void realJourneyProgressStartsFreshRecoveryEpisode() {
        var watchdog = new NavigationDispatchWatchdog();
        long tick = 0;
        watchdog.observe(++tick, true, true, true);
        for (int i = 0; i < 39; i++) watchdog.observe(++tick, true, false, false);
        assertEquals(NavigationDispatchWatchdog.Action.RESTART,
                watchdog.observe(++tick, true, false, false),
                "actual journey progress starts a fresh recovery episode");
    }
}
