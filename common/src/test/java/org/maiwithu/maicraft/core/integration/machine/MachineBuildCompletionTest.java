// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.Map;

/** Construction acceptance must not turn tutorial geometry into claimed configuration or production. */
public final class MachineBuildCompletionTest {
    public static void main(String[] args) {
        var explicit = new MachineBuildCompletion(true);
        verify(explicit.report(), false, false, false, "separate_use_phase");
        Map<String, Object> before = explicit.report();
        check(explicit.acceptGeometry(), "explicit geometry must finish construction without operating the machine");
        verify(explicit.report(), true, true, false, "separate_use_phase");
        verify(before, false, false, false, "separate_use_phase");
        rejectsCommissioning(explicit);
        verify(explicit.report(), true, true, false, "separate_use_phase");

        var semantic = new MachineBuildCompletion(false);
        verify(semantic.report(), false, false, false, "pending");
        rejectsCommissioning(semantic);
        check(!semantic.acceptGeometry(), "semantic geometry must retain native commissioning obligations");
        // Until commissioning succeeds, a timeout/failure receipt must retain incomplete configuration.
        verify(semantic.report(), true, false, false, "pending");
        semantic.acceptCommissioning();
        verify(semantic.report(), true, true, true, "complete");
        System.out.println("MachineBuildCompletionTest: passed");
    }

    private static void rejectsCommissioning(MachineBuildCompletion completion) {
        try { completion.acceptCommissioning(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("commissioning accepted outside verified semantic construction");
    }

    private static void verify(Map<String, Object> report, boolean geometry, boolean built,
            boolean configured, String status) {
        check(Boolean.valueOf(geometry).equals(report.get("machine_geometry_verified")), "incorrect geometry evidence");
        check(Boolean.valueOf(built).equals(report.get("construction_complete")), "incorrect construction completion");
        check(Boolean.valueOf(configured).equals(report.get("configuration_complete")), "incorrect configuration claim");
        check(Boolean.FALSE.equals(report.get("machine_production_verified")), "construction cannot prove production");
        check(status.equals(report.get("configuration_status")), "incorrect configuration phase");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
