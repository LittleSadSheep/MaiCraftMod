package org.maiwithu.maicraft.mcp.transport;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;
import org.maiwithu.maicraft.core.build.BuildingBudgets;

/** 本地 MCP 服务的监听地址、端口、可选口令、请求大小和等待回复时限。 */
public record McpConfig(
        String host,
        int port,
        String bearerToken,
        int maxRequestBytes,
        Duration requestTimeout
) {
    public McpConfig {
        // 配置不合法就在启动前拒绝；监听地址只允许本机回环地址，不能直接开放到局域网或公网。
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(bearerToken, "bearerToken");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        // 大模型请求按建筑配置定额接收；这里只保留读取器可表达的 int 边界，不再暗中压回十六 MiB。
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
     * 读取本进程的 MCP 端口：未设置时用默认值，显式设置是固定端口身份的首选方式。
     * 端口被占的自动让行在启动处处理（EmbeddedMcpService.startWithFallback）；
     * 这里只负责非法配置在启动前失败，不猜测调用方意图。
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
        // 继续复用回环地址与端口校验；越界或非整数的错误配置停止 MCP 启动。
        return local(port);
    }

    public static McpConfig local(int port) {
        // 客户端启动时按同一份建筑配置创建服务；后续只改文件不会改变已运行服务器的请求预算。
        return new McpConfig(
                "127.0.0.1",
                port,
                "",
                BuildingBudgets.current().maxMcpRequestBytes(),
                Duration.ofSeconds(15)
        );
    }
}
