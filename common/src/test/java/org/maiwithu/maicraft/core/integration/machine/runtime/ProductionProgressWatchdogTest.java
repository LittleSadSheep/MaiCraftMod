// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

public final class ProductionProgressWatchdogTest {
    public static void main(String[] args) {
        var watch = new ProductionProgressWatchdog(600, 300, 5);
        check(watch.failure(500, 500, -1, false, -1, true, null) == null, "first processing has an explicit initial allowance");
        check(watch.failure(601, 601, -1, false, -1, true, null).contains("budget_exhausted"), "missing processing still has a bounded outcome");
        watch.noteInput(10000);
        check(watch.failure(10020, 9000, 100, false, -1, true, "source failure") == null, "long acquisition cannot expire freshly deposited input");
        check(watch.failure(10301, 301, 10000, false, -1, true, null).contains("budget_exhausted"), "confirmed input without later progress stops");
        check(watch.failure(10301, 20, 10281, false, -1, true, null) == null, "upstream native processing resets idle");
        check(watch.deliveryFailure(10000, -1, -1) == null, "unfinished navigation is not an idle observation");
        check(watch.deliveryFailure(10400, 400, -1) == null, "finished finite processing still allows real delivery time");
        check(watch.deliveryFailure(11300, 1300, -1).contains("delivery_stalled"), "missing native delivery cannot wait forever");
        check(watch.deliveryFailure(11300, 1300, 11200) == null, "new attributed delivery resets transport waiting");
        var longLine = new ProductionProgressWatchdog(1200, 1200, 41);
        check(longLine.deliveryFailure(2800, 2800, -1) == null, "long paths receive a distinct transport allowance");
        check(longLine.deliveryFailure(2841, 2841, -1) != null, "long delivery allowance remains finite");
        System.out.println("ProductionProgressWatchdogTest: passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
