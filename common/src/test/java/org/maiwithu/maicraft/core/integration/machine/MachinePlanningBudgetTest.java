// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.Properties;

/** Pure Java regression suite, independent of Minecraft bootstrap, system-property mutation or Gradle. */
public final class MachinePlanningBudgetTest {
    public static void main(String[] args) {
        var defaults = MachinePlanningBudget.defaults();
        check(defaults.maxTargets() == 32768 && defaults.maxComponents() == 1024
                && defaults.maxConnections() == 4096 && defaults.maxRadius() == 128
                && defaults.searchVisitedBudget() == 200000, "established defaults retained");
        check(defaults.diagnostics().isEmpty(), "unset properties are not errors");
        Properties properties = new Properties();
        properties.setProperty(key("maxTargets"), "1000000");
        properties.setProperty(key("maxComponents"), "10000");
        properties.setProperty(key("maxConnections"), "50000");
        properties.setProperty(key("maxRadius"), " 512 ");
        properties.setProperty(key("searchVisitedBudget"), "2000000");
        var expanded = MachinePlanningBudget.fromProperties(properties);
        check(expanded.maxTargets() == 1000000 && expanded.maxComponents() == 10000
                && expanded.maxConnections() == 50000 && expanded.maxRadius() == 512
                && expanded.searchVisitedBudget() == 2000000, "every budget can be independently expanded");
        check(expanded.checkTargets(1000000).allowed() && !expanded.checkTargets(1000001).allowed(), "target budget boundary");
        check(expanded.checkComponents(10000).allowed() && !expanded.checkConnections(50001).allowed(), "logical graph budget boundaries");
        check(!expanded.checkTargets(-1).allowed(), "wrapped negative target counts rejected");
        check(!expanded.checkTargetSum(Long.MAX_VALUE, 1).allowed(), "sum overflow fails before allocation");
        check(expanded.checkTargetSum(Long.MAX_VALUE, 1).code().equals("target_count_overflow"), "sum overflow diagnostic");
        check(!expanded.checkVolume(Long.MAX_VALUE, 2, 2).allowed(), "volume overflow fails before allocation");
        check(!expanded.checkVolume(2, -2, 4).allowed(), "invalid dimensions are not an affordable plan");
        check(expanded.checkVolume(100, 100, 100).allowed(), "expanded budgets admit larger physical layouts");
        for (String invalid : new String[]{"", " ", "0", "-1", "1.5", "1e6", "NaN", "2147483648", "999999999999999999999999999999"}) {
            Properties bad = new Properties(); bad.setProperty(key("maxTargets"), invalid);
            var fallback = MachinePlanningBudget.fromProperties(bad);
            check(fallback.maxTargets() == defaults.maxTargets() && fallback.diagnostics().size() == 1, "invalid values fall back with one actionable diagnostic: " + invalid);
            check(fallback.diagnostics().getFirst().property().equals(key("maxTargets")), "diagnostic identifies exact JVM property");
        }
        Properties overflowRadius = new Properties(); overflowRadius.setProperty(key("maxRadius"), "2147483647");
        check(MachinePlanningBudget.fromProperties(overflowRadius).maxRadius() == 128, "radius cannot overflow int diameter");
        Properties integerMaximum = new Properties(); integerMaximum.setProperty(key("maxTargets"), "2147483647");
        check(MachinePlanningBudget.fromProperties(integerMaximum).maxTargets() == Integer.MAX_VALUE, "no arbitrary smaller target limit replaces the owner's setting");
        var inaccessible = MachinePlanningBudget.fromLookup(ignored -> { throw new SecurityException("denied"); });
        check(inaccessible.maxTargets() == defaults.maxTargets() && inaccessible.diagnostics().size() == 5, "restricted property access never crashes initialization");
        properties.setProperty(key("maxTargets"), "1");
        check(expanded.maxTargets() == 1000000, "a compiled plan's resource snapshot remains immutable");
        System.out.println("MachinePlanningBudgetTest: passed");
    }
    private static String key(String suffix) { return MachinePlanningBudget.PROPERTY_PREFIX + suffix; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
