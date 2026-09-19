// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.build;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

/** 用真实配置文件模拟玩家调高航站楼预算，核对启动快照、非法值回退与用户原文件保留。 */
public final class BuildingBudgetsTest {
    public static void main(String[] args) throws Exception {
        var defaults = BuildingBudgets.defaults();
        check(defaults.maxTargets() >= 120 * 80 * 18 && defaults.maxPreviewCells() >= defaults.maxTargets(),
                "default building and preview budgets cover the reference terminal");
        Properties custom = new Properties();
        custom.setProperty("maxTargets", "1000000"); custom.setProperty("maxRadius", "2048");
        custom.setProperty("maxProjectBytes", "536870912"); custom.setProperty("preview.frameMillis", "0.75");
        var configured = BuildingBudgets.fromProperties(custom);
        check(configured.maxTargets() == 1_000_000 && configured.maxRadius() == 2048
                && configured.maxProjectBytes() == 536_870_912 && configured.previewFrameMillis() == .75,
                "explicit owner limits are not clamped to former budgets");
        custom.setProperty("maxTargets", "2");
        check(configured.maxTargets() == 1_000_000, "budget snapshot cannot follow mutable input properties");
        for (String invalid : new String[]{"0", "-2", "abc", "2147483648", "2.5"}) {
            Properties bad = new Properties(); bad.setProperty("maxTargets", invalid); bad.setProperty("maxObjects", "23");
            var fallback = BuildingBudgets.fromProperties(bad);
            check(fallback.maxTargets() == defaults.maxTargets() && fallback.maxObjects() == 23
                    && fallback.diagnostics().size() == 1, "only malformed field falls back: " + invalid);
        }
        Properties unsafe = new Properties();
        unsafe.setProperty("maxProjectBytes", "2147483647"); unsafe.setProperty("preview.frameMillis", "NaN");
        unsafe.setProperty("maxRadius", "2147483647"); unsafe.setProperty("maxMcpRequestBytes", "100");
        check(BuildingBudgets.fromProperties(unsafe).diagnostics().size() == 4, "sentinel, frame, radius and transport ranges stay representable");

        var directory = Files.createTempDirectory("maicraft-building-budget-");
        var restore = Files.createTempDirectory("maicraft-building-defaults-");
        try {
            var file = directory.resolve(BuildingBudgets.CONFIG_PATH);
            BuildingBudgets.initialize(directory);
            String generated = Files.readString(file, StandardCharsets.UTF_8);
            check(generated.contains("maxTargets=262144") && generated.contains("修改后重启"), "first startup creates documented owner config");
            String selected = "# 保留玩家说明\nmaxTargets=1000000\npreview.frameMillis=0.5\nunknownFutureOption=keep\n";
            Files.writeString(file, selected, StandardCharsets.UTF_8);
            check(BuildingBudgets.current().maxTargets() == defaults.maxTargets(), "editing disk does not alter an active build snapshot");
            BuildingBudgets.initialize(directory);
            check(BuildingBudgets.current().maxTargets() == 1_000_000 && BuildingBudgets.current().previewFrameMillis() == .5,
                    "next startup activates configured limits");
            check(Files.readString(file, StandardCharsets.UTF_8).equals(selected), "owner comments and unknown future options remain untouched");
        } finally {
            // 独立回归恢复默认预算，避免这一场百万格配置影响后面的普通建造测试。
            BuildingBudgets.initialize(restore);
        }
        System.out.println("BuildingBudgetsTest: passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
