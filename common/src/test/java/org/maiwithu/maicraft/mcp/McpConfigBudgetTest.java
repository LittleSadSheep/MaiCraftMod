// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import org.maiwithu.maicraft.core.build.BuildingBudgets;

/** 建筑请求采用有效启动预算；放大容量不改变回环监听、端口与读取器整数边界。 */
public final class McpConfigBudgetTest {
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of("").toAbsolutePath().normalize();
        Path directory = Files.createTempDirectory(workspace, "mcp-building-budget-");
        try {
            BuildingBudgets.initialize(directory.resolve("defaults"));
            var original = McpConfig.local(0);
            check(original.maxRequestBytes() == 64 * 1024 * 1024, "正常启动应采用六十四 MiB 请求预算");
            configure(directory, 4096);
            check(McpConfig.local(0).maxRequestBytes() == 4096 && original.maxRequestBytes() == 64 * 1024 * 1024,
                    "新服务读取新启动配置，已有服务仍保留创建时的请求预算");
            configure(directory, 128 * 1024 * 1024);
            check(McpConfig.local(0).maxRequestBytes() == 128 * 1024 * 1024, "有效配置不能被旧十六 MiB 隐藏上限截断");
            check(config(Integer.MAX_VALUE - 1).maxRequestBytes() == Integer.MAX_VALUE - 1, "构造器保留实际 int 读取上界");
            rejected(() -> config(Integer.MAX_VALUE), "超出读取器边界必须拒绝");
            rejected(() -> config(1023), "请求预算不能小于最小报文容量");
            rejected(() -> new McpConfig("0.0.0.0",0,"",64*1024*1024,Duration.ofSeconds(15)), "提高容量不允许对外网监听");
            rejected(() -> new McpConfig("127.0.0.1",65536,"",4096,Duration.ofSeconds(15)), "非法端口仍需拒绝");
            rejected(() -> new McpConfig("127.0.0.1",0,"",4096,Duration.ZERO), "非法等待时限仍需拒绝");
            System.out.println("McpConfigBudgetTest: passed");
        } finally {
            // 测试只改自己临时目录的配置；退出时恢复默认预算，再验证路径并清理夹具。
            BuildingBudgets.initialize(directory.resolve("restore-defaults"));
            if (!directory.toRealPath().startsWith(workspace.toRealPath())) throw new AssertionError("budget fixture escaped workspace");
            try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    private static void configure(Path directory, int bytes) throws Exception {
        Path game = directory.resolve("configured"); Path config = game.resolve(BuildingBudgets.CONFIG_PATH);
        Files.createDirectories(config.getParent()); Files.writeString(config, "maxMcpRequestBytes=" + bytes + "\n"); BuildingBudgets.initialize(game);
    }
    private static McpConfig config(int bytes) { return new McpConfig("127.0.0.1",0,"",bytes,Duration.ofSeconds(15)); }
    private static void rejected(Runnable action, String message) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError(message); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
