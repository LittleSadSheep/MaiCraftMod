// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 配置在启动前就拒绝非法值：回环地址之外、越界端口、过小或越界的请求预算、非正的等待时限都不放行。 */
class McpConfigTest {
    @Test
    void localDefaultsServeLoopbackWithGenerousRequestBudget() {
        McpConfig config = McpConfig.local(0);
        assertEquals("127.0.0.1", config.host());
        assertEquals(0, config.port());
        assertEquals("", config.bearerToken());
        assertEquals(McpConfig.DEFAULT_MAX_REQUEST_BYTES, config.maxRequestBytes());
        assertEquals(Duration.ofSeconds(15), config.requestTimeout());
    }

    @Test
    void localForProcessReadsConfiguredPortAndRejectsGarbage() {
        String previous = System.getProperty("maicraft.mcp.port");
        try {
            System.clearProperty("maicraft.mcp.port");
            assertEquals(25500, McpConfig.localForProcess(25500).port());
            System.setProperty("maicraft.mcp.port", " 26001 ");
            assertEquals(26001, McpConfig.localForProcess(25500).port());
            System.setProperty("maicraft.mcp.port", "not-a-number");
            assertThrows(IllegalArgumentException.class, () -> McpConfig.localForProcess(25500));
        } finally {
            if (previous == null) System.clearProperty("maicraft.mcp.port");
            else System.setProperty("maicraft.mcp.port", previous);
        }
    }

    @Test
    void rejectsValuesOutsideTheReadableRange() {
        assertThrows(IllegalArgumentException.class,
                () -> config("0.0.0.0", 0, 4096, Duration.ofSeconds(15)), "不允许对外网监听");
        assertThrows(IllegalArgumentException.class,
                () -> config("127.0.0.1", 65536, 4096, Duration.ofSeconds(15)), "非法端口仍需拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> config("127.0.0.1", 0, 4096, Duration.ZERO), "等待时限必须为正");
        assertThrows(IllegalArgumentException.class,
                () -> config("127.0.0.1", 0, 1023, Duration.ofSeconds(15)), "请求预算不能小于最小报文容量");
        assertThrows(IllegalArgumentException.class,
                () -> config("127.0.0.1", 0, Integer.MAX_VALUE, Duration.ofSeconds(15)), "超出读取上界必须拒绝");
        assertEquals(Integer.MAX_VALUE - 1,
                config("127.0.0.1", 0, Integer.MAX_VALUE - 1, Duration.ofSeconds(15)).maxRequestBytes(),
                "构造器保留实际可读取的上界");
    }

    private static McpConfig config(String host, int port, int maxRequestBytes, Duration timeout) {
        return new McpConfig(host, port, "", maxRequestBytes, timeout);
    }
}
