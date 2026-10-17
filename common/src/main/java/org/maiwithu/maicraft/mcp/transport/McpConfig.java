// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.transport;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;

/** 内嵌 MCP 服务的监听地址、端口、可选口令、请求大小上限与等待回复时限。 */
public record McpConfig(
        String host,
        int port,
        String bearerToken,
        int maxRequestBytes,
        Duration requestTimeout
) {
    /** 一次请求能送进来的最大字节数；长观察与蓝图都应完整送达，不暗中截断。 */
    public static final int DEFAULT_MAX_REQUEST_BYTES = 64 * 1024 * 1024;

    /** 默认等待回复时限；工具的真实行为接入后，长等待的调用在此基础上追加等待时长。 */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(15);

    public McpConfig {
        // 配置不合法就在启动前拒绝；监听地址只允许本机回环地址，不能直接开放到局域网或公网。
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(bearerToken, "bearerToken");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        // 请求体读取按真实收到的字节数累计，这里保留 int 可表达的边界。
        if (maxRequestBytes < 1_024 || maxRequestBytes > Integer.MAX_VALUE - 1) {
            throw new IllegalArgumentException("maxRequestBytes is outside the safe range");
        }
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        try {
            if (!InetAddress.getByName(host).isLoopbackAddress()) {
                throw new IllegalArgumentException("the embedded MCP server may only bind a loopback address");
            }
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("invalid MCP bind host", exception);
        }
    }

    /**
     * 读取系统属性里配置的本进程 MCP 端口：未设置时用默认值；端口被占的自动让行由启动处处理，
     * 这里只负责非法配置在启动前失败。
     */
    public static McpConfig localForProcess(int defaultPort) {
        String configured = System.getProperty("maicraft.mcp.port");
        int port = defaultPort;
        if (configured != null) {
            try {
                port = Integer.parseInt(configured.strip());
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("maicraft.mcp.port must be an integer between 0 and 65535", invalid);
            }
        }
        return local(port);
    }

    /** 本机回环地址上的默认配置；口令留空表示不启用鉴权检查。 */
    public static McpConfig local(int port) {
        return new McpConfig("127.0.0.1", port, "", DEFAULT_MAX_REQUEST_BYTES, DEFAULT_REQUEST_TIMEOUT);
    }
}
