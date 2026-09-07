// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Owner-configurable memory/work budgets; these do not replace an installed mod's physical rules. */
public final class MachinePlanningBudget {
    public static final String PROPERTY_PREFIX = "maicraft.machine.planning.";
    public static final int DEFAULT_MAX_TARGETS = 32_768;
    public static final int DEFAULT_MAX_COMPONENTS = 1_024;
    public static final int DEFAULT_MAX_CONNECTIONS = 4_096;
    public static final int DEFAULT_MAX_RADIUS = 128;
    public static final int DEFAULT_SEARCH_VISITED_BUDGET = 200_000;
    private final int maxTargets, maxComponents, maxConnections, maxRadius, searchVisitedBudget;
    private final List<Diagnostic> diagnostics;

    public record Diagnostic(String property, String suppliedValue, String code, int fallback, String message) {}
    public record Check(boolean allowed, String code, long requested, long limit) {}

    private MachinePlanningBudget(Function<String, String> source) {
        List<Diagnostic> issues = new ArrayList<>();
        maxTargets = read(source, "maxTargets", DEFAULT_MAX_TARGETS, Integer.MAX_VALUE, issues);
        maxComponents = read(source, "maxComponents", DEFAULT_MAX_COMPONENTS, Integer.MAX_VALUE, issues);
        maxConnections = read(source, "maxConnections", DEFAULT_MAX_CONNECTIONS, Integer.MAX_VALUE, issues);
        // The current coordinate representation computes an inclusive diameter as 2 * radius + 1.
        maxRadius = read(source, "maxRadius", DEFAULT_MAX_RADIUS, (Integer.MAX_VALUE - 1) / 2, issues);
        searchVisitedBudget = read(source, "searchVisitedBudget", DEFAULT_SEARCH_VISITED_BUDGET, Integer.MAX_VALUE, issues);
        diagnostics = List.copyOf(issues);
    }

    /** One immutable startup snapshot, so a layout cannot change resource budgets midway through planning. */
    public static MachinePlanningBudget current() { return SystemBudget.INSTANCE; }
    public static MachinePlanningBudget defaults() { return new MachinePlanningBudget(ignored -> null); }
    public static MachinePlanningBudget fromProperties(Properties properties) {
        return new MachinePlanningBudget(properties == null ? ignored -> null : properties::getProperty);
    }
    public static MachinePlanningBudget fromLookup(Function<String, String> source) {
        return new MachinePlanningBudget(source == null ? ignored -> null : source);
    }

    public int maxTargets() { return maxTargets; }
    public int maxComponents() { return maxComponents; }
    public int maxConnections() { return maxConnections; }
    public int maxRadius() { return maxRadius; }
    public int searchVisitedBudget() { return searchVisitedBudget; }
    public List<Diagnostic> diagnostics() { return diagnostics; }

    public Check checkTargets(long requested) { return check(requested, maxTargets, "target"); }
    public Check checkComponents(long requested) { return check(requested, maxComponents, "component"); }
    public Check checkConnections(long requested) { return check(requested, maxConnections, "connection"); }

    /** Check before allocation or an int cast; wrapped arithmetic must never admit an oversized layout. */
    public Check checkTargetSum(long first, long second) {
        if (first < 0 || second < 0) return new Check(false, "invalid_target_count", -1, maxTargets);
        try { return checkTargets(Math.addExact(first, second)); }
        catch (ArithmeticException overflow) { return new Check(false, "target_count_overflow", Long.MAX_VALUE, maxTargets); }
    }
    public Check checkVolume(long width, long height, long depth) {
        if (width <= 0 || height <= 0 || depth <= 0) return new Check(false, "invalid_dimensions", -1, maxTargets);
        try { return checkTargets(Math.multiplyExact(Math.multiplyExact(width, height), depth)); }
        catch (ArithmeticException overflow) { return new Check(false, "target_count_overflow", Long.MAX_VALUE, maxTargets); }
    }

    private static Check check(long requested, int limit, String kind) {
        if (requested < 0) return new Check(false, "invalid_" + kind + "_count", requested, limit);
        return new Check(requested <= limit, requested <= limit ? "within_budget" : kind + "_budget_exceeded", requested, limit);
    }

    private static int read(Function<String, String> source, String field, int fallback, int maximum, List<Diagnostic> issues) {
        String property = PROPERTY_PREFIX + field;
        String raw;
        try { raw = source.apply(property); }
        catch (RuntimeException unavailable) {
            issues.add(new Diagnostic(property, "<unavailable>", "property_read_failed", fallback,
                    "Cannot read " + property + " (" + unavailable.getClass().getSimpleName() + "); using " + fallback + '.'));
            return fallback;
        }
        if (raw == null) return fallback;
        String value = raw.trim();
        try {
            if (value.isEmpty() || value.length() > 10 || !value.chars().allMatch(Character::isDigit)) throw new NumberFormatException();
            long parsed = Long.parseLong(value);
            if (parsed < 1 || parsed > maximum) throw new NumberFormatException();
            return (int) parsed;
        } catch (NumberFormatException invalid) {
            String reason = field.equals("maxRadius") ? "must be positive and its 2 * radius + 1 diameter must fit a signed 32-bit integer"
                    : "must be a positive signed 32-bit integer";
            String displayed = raw.length() <= 160 ? raw : raw.substring(0, 157) + "...";
            issues.add(new Diagnostic(property, displayed, "invalid_property_value", fallback,
                    property + " " + reason + "; received '" + displayed + "', using " + fallback + '.'));
            return fallback;
        }
    }

    private static final class SystemBudget {
        private static final MachinePlanningBudget INSTANCE = load();
        private static MachinePlanningBudget load() {
            MachinePlanningBudget budget = fromLookup(System::getProperty);
            for (Diagnostic diagnostic : budget.diagnostics()) {
                try { System.getLogger(MachinePlanningBudget.class.getName()).log(System.Logger.Level.WARNING, diagnostic.message()); }
                catch (RuntimeException unavailable) { /* Config diagnostics remain accessible even if logging is restricted. */ }
            }
            return budget;
        }
    }
}
