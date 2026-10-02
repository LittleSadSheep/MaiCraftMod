package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.pathing.calc.GroundJourneyContinuationTest;
import java.util.concurrent.TimeUnit;

/** 每轮使用全新 JVM，交替初始化顺序，避免复用一个已预热的会话掩盖首任务故障。 */
public final class NavigationStartupRegressionSuite {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            // 两种启动顺序都覆盖原生十九格寻路和三十六种背包槽位，界面故意一直不绘制。
            if (args[0].equals("path_first")) GroundJourneyContinuationTest.main(new String[]{"startup_19"});
            NavigationStartupPreparationTest.main(new String[0]);
            if (!args[0].equals("path_first")) GroundJourneyContinuationTest.main(new String[]{"startup_19"});
            return;
        }
        String java = ProcessHandle.current().info().command().orElseThrow();
        for (int session = 0; session < 4; session++) {
            var process = new ProcessBuilder(java, "-Xmx512m", "-cp", System.getProperty("java.class.path"),
                    NavigationStartupRegressionSuite.class.getName(), session % 2 == 0 ? "path_first" : "inventory_first")
                    .inheritIO().start();
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly(); throw new AssertionError("fresh navigation session timed out: " + session);
            }
            if (process.exitValue() != 0) throw new AssertionError("fresh navigation session failed: " + session);
        }
        System.out.println("NavigationStartupRegressionSuite: four independent JVM sessions passed");
    }
}
