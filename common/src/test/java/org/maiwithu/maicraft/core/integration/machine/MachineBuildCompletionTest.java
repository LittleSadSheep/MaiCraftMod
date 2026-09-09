// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.Map;

/**
 * 检查机器验收报告的措辞边界：显式蓝图摆好即可完成建造，语义设计还需配置检查；两者都不能声称已经生产。
 * 也检查旧报告不会随对象后续变化、错误顺序不能提前通过配置验收；这里不启动游戏或验证实际装配。
 */
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
        // 配置验收尚未成功时，即使方块摆好了，超时或失败报告也必须保留“配置未完成”。
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
