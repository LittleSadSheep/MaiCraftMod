package org.maiwithu.maicraft.core.pathing.baritone;

/** 默认纯行走也会经过调度健康检查；复现请求消失、暂停与实际行走后恢复预算的不同情况。 */
public final class NavigationDispatchWatchdogTest {
    public static void main(String[] args) {
        var watchdog = new NavigationDispatchWatchdog(); long tick = 0;
        for (int i = 0; i < 200; i++) check(watchdog.observe(++tick, false, false, false) == NavigationDispatchWatchdog.Action.NONE,
                "waiting for native confirmation or human control must not trigger a reset");
        for (int i = 0; i < 39; i++) check(watchdog.observe(++tick, true, false, false) == NavigationDispatchWatchdog.Action.NONE,
                "normal first-frame dispatch receives a bounded grace period");
        check(watchdog.observe(++tick, true, false, false) == NavigationDispatchWatchdog.Action.RESTART, "lost request is restarted once");
        check(watchdog.observe(tick, true, false, false) == NavigationDispatchWatchdog.Action.NONE, "repeated observation in one body tick does not consume another tick");
        for (int i = 0; i < 39; i++) watchdog.observe(++tick, true, false, false);
        check(watchdog.observe(++tick, true, false, false) == NavigationDispatchWatchdog.Action.FAIL, "repeated zero-search state fails explicitly rather than retrying forever");
        for (int i = 0; i < 100; i++) check(watchdog.observe(++tick, true, true, false) == NavigationDispatchWatchdog.Action.NONE,
                "active search, existing movement or confirmed no-solution is not a lost dispatch");
        watchdog.observe(++tick, true, true, true);
        for (int i = 0; i < 39; i++) watchdog.observe(++tick, true, false, false);
        check(watchdog.observe(++tick, true, false, false) == NavigationDispatchWatchdog.Action.RESTART, "actual journey progress starts a fresh recovery episode");
        System.out.println("NavigationDispatchWatchdogTest: authorized idle dispatch and recovery limits passed");
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
