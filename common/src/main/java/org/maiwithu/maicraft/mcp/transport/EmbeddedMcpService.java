// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.transport;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.maiwithu.maicraft.mcp.tool.ToolCatalog;

/**
 * 游戏进程里的内嵌 MCP 服务：接收请求、检查连接和格式，再把工具调用交给工具层。
 *
 * <p>这里只关心传输：端口与会话、协议版本协商、鉴权、请求大小与超时配置。
 * 一个网络连接不是一个游戏任务；断开连接或停止等回复，不等于取消已经开始的游戏任务。
 */
public final class EmbeddedMcpService implements AutoCloseable {
    private static final String ENDPOINT = "/mcp";
    private static final String SESSION_HEADER = "MCP-Session-Id";
    private static final String VERSION_HEADER = "MCP-Protocol-Version";
    private static final String LATEST_VERSION = "2025-11-25";
    private static final Set<String> SUPPORTED_VERSIONS = Set.of(
            LATEST_VERSION, "2025-06-18", "2025-03-26"
    );
    // 客户端没写版本请求头时按最早的兼容版本处理；只有仍受支持的头才允许带着会话继续。
    private static final String OLDEST_SUPPORTED_VERSION = "2025-03-26";
    private static final int MAX_SESSIONS = 64;
    private static final long DEFAULT_SESSION_TTL_NANOS = Duration.ofMinutes(10).toNanos();
    private static final long SESSION_REAP_SECONDS = 30L;

    private final McpConfig config;
    private final long sessionTtlNanos;
    private final ConcurrentMap<String, McpSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean stopping = new AtomicBoolean();

    private HttpServer server;
    private ExecutorService executor;
    private ScheduledExecutorService maintenance;
    private Thread shutdownHook;

    public EmbeddedMcpService(McpConfig config) {
        this(config, DEFAULT_SESSION_TTL_NANOS);
    }

    /** 会话时限按纳秒注入，供离线测试把空闲回收压缩到可控时长。 */
    EmbeddedMcpService(McpConfig config, long sessionTtlNanos) {
        this.config = Objects.requireNonNull(config, "config");
        this.sessionTtlNanos = sessionTtlNanos;
    }

    /** 启动本地端口和网络线程；已经启动时不重复开一个服务。 */
    public synchronized void start() throws IOException {
        if (server != null) return;
        stopping.set(false);

        // 先在局部变量里准备各部分；任何一步失败都关闭已创建的资源，全部成功后才记为服务已启动。
        ExecutorService newExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("maicraft-mcp-", 0).factory());
        ScheduledExecutorService newMaintenance = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("maicraft-mcp-maintenance-", 0).factory());
        HttpServer newServer = null;
        try {
            newServer = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
            newServer.createContext(ENDPOINT, this::handle);
            newServer.setExecutor(newExecutor);
            newServer.start();
            newMaintenance.scheduleWithFixedDelay(
                    this::reapSessionsSafely, SESSION_REAP_SECONDS, SESSION_REAP_SECONDS, TimeUnit.SECONDS);

            executor = newExecutor;
            maintenance = newMaintenance;
            server = newServer;
            installShutdownHook();
        } catch (IOException | RuntimeException exception) {
            if (newServer != null) newServer.stop(0);
            newExecutor.shutdownNow();
            newMaintenance.shutdownNow();
            throw exception;
        }
    }

    /**
     * 按配置端口启动服务；端口已被占用时依次让行到后续端口，最多再试 {@code extraAttempts} 个。
     * 让行只针对绑定失败（端口不可用），服务自身的其他失败原样抛出；全部端口都不可用时抛出
     * 最后一次绑定异常。配置端口为 0 时由系统分配，不会触发让行。
     */
    public static EmbeddedMcpService startWithFallback(McpConfig config, int extraAttempts) throws IOException {
        IOException lastBindFailure = null;
        for (int attempt = 0; attempt <= extraAttempts; attempt++) {
            int port = config.port() + attempt;
            if (port > 65_535) break;
            EmbeddedMcpService candidate = new EmbeddedMcpService(new McpConfig(
                    config.host(), port, config.bearerToken(), config.maxRequestBytes(), config.requestTimeout()));
            try {
                candidate.start();
                return candidate;
            } catch (IOException bindFailure) {
                closeQuietly(candidate);
                lastBindFailure = bindFailure;
            }
        }
        throw lastBindFailure != null ? lastBindFailure
                : new IOException("no port to bind in range " + config.port() + "+");
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    /** 返回实际端口，包括指定端口为 0 时系统选择的临时端口。 */
    public synchronized int port() {
        if (server == null) return -1;
        return server.getAddress().getPort();
    }

    /** 返回当前活动的 MCP 传输会话数，供本地只读状态查询使用。 */
    public int sessionCount() {
        return sessions.size();
    }

    /** 停止接收网络请求并关闭所有会话；游戏任务的清理由启动层另外负责。 */
    public synchronized void stop() {
        if (server == null && executor == null && maintenance == null) return;
        stopping.set(true);

        ScheduledExecutorService currentMaintenance = maintenance;
        maintenance = null;
        if (currentMaintenance != null) currentMaintenance.shutdownNow();

        sessions.values().forEach(McpSession::close);
        sessions.clear();

        HttpServer currentServer = server;
        server = null;
        if (currentServer != null) currentServer.stop(0);

        ExecutorService currentExecutor = executor;
        executor = null;
        if (currentExecutor != null) currentExecutor.shutdownNow();
        removeShutdownHook();
    }

    @Override
    public void close() {
        stop();
    }

    private void handle(HttpExchange exchange) throws IOException {
        // 先查是否停服、浏览器来源是否允许、口令是否正确，再按 POST／DELETE 处理请求。
        try {
            if (stopping.get()) {
                sendStatus(exchange, 503, "MCP endpoint is stopping");
                return;
            }
            if (!validOrigin(exchange.getRequestHeaders().getFirst("Origin"))) {
                sendStatus(exchange, 403, "Origin is not allowed");
                return;
            }
            if (!authenticated(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                sendStatus(exchange, 401, "Authentication required");
                return;
            }
            switch (exchange.getRequestMethod().toUpperCase(Locale.ROOT)) {
                case "POST" -> handlePost(exchange);
                case "DELETE" -> handleDelete(exchange);
                default -> {
                    exchange.getResponseHeaders().set("Allow", "POST, DELETE");
                    sendStatus(exchange, 405, "Method not allowed");
                }
            }
        } catch (PayloadTooLargeException exception) {
            sendStatusSafely(exchange, 413, exception.getMessage());
        } catch (Exception exception) {
            sendStatusSafely(exchange, 500, "Internal MCP transport error");
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        // 普通 MCP 请求用 JSON；先检查内容类型和大小，再解析 JSON-RPC 方法与请求编号。
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            sendStatus(exchange, 415, "Content-Type must be application/json");
            return;
        }
        String accept = exchange.getRequestHeaders().getFirst("Accept");
        if (accept != null && !accept.contains("application/json") && !accept.contains("*/*")) {
            sendStatus(exchange, 406, "Accept must include application/json");
            return;
        }

        JsonObject request;
        try {
            JsonElement parsed = JsonParser.parseString(readBody(exchange));
            if (!parsed.isJsonObject()) {
                sendJson(exchange, 200, JsonRpc.error(JsonNull.INSTANCE, -32600, "Invalid Request"), null);
                return;
            }
            request = parsed.getAsJsonObject();
        } catch (JsonParseException exception) {
            sendJson(exchange, 200, JsonRpc.error(JsonNull.INSTANCE, -32700, "Parse error"), null);
            return;
        }

        JsonElement id = request.has("id") ? request.get("id") : null;
        String method;
        try {
            if (!"2.0".equals(JsonRpc.requiredString(request, "jsonrpc"))) {
                throw new IllegalArgumentException("jsonrpc must be 2.0");
            }
            method = JsonRpc.requiredString(request, "method");
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 200, JsonRpc.error(JsonRpc.idOrNull(id), -32600, "Invalid Request"), null);
            return;
        }

        // 没有请求编号的是通知，不返回调用结果；初始化完成通知在这里登记会话状态。
        if (id == null) {
            McpSession session = requireSession(exchange);
            if (session == null || !validVersionHeader(exchange, session)) return;
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }

        if ("initialize".equals(method)) {
            handleInitialize(exchange, id, request.get("params"));
            return;
        }

        McpSession session = requireSession(exchange);
        if (session == null) return;
        if (!validVersionHeader(exchange, session)) return;

        JsonObject response;
        try {
            response = JsonRpc.success(id, dispatch(method, JsonRpc.optionalObject(request.get("params"))));
        } catch (JsonRpc.RpcException exception) {
            response = JsonRpc.error(id, exception.code(), exception.getMessage());
        } catch (IllegalArgumentException exception) {
            response = JsonRpc.error(id, -32602, exception.getMessage());
        } catch (Exception exception) {
            response = JsonRpc.error(id, -32603, "Internal error");
        }
        sendJson(exchange, 200, response, session);
    }

    private void handleInitialize(HttpExchange exchange, JsonElement id, JsonElement rawParams) throws IOException {
        // 协商协议版本并分配连接编号；能力声明只报告工具，还没有可订阅的资源。
        JsonObject params = JsonRpc.optionalObject(rawParams);
        String requested = params.has("protocolVersion") && params.get("protocolVersion").isJsonPrimitive()
                ? params.get("protocolVersion").getAsString()
                : LATEST_VERSION;
        String negotiated = SUPPORTED_VERSIONS.contains(requested) ? requested : LATEST_VERSION;

        McpSession session = createSession(negotiated);

        JsonObject capabilities = new JsonObject();
        JsonObject tools = new JsonObject();
        tools.addProperty("listChanged", false);
        capabilities.add("tools", tools);

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "maicraft");
        serverInfo.addProperty("version", "1.0.0");

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", negotiated);
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);
        result.addProperty("instructions",
                "MaiCraft controls the local player in a Minecraft world. "
                        + "Five tools are available: observe, lookup, execute, task, events. "
                        + "Some tool behaviors are still being migrated; a call may answer that the tool is not ready yet.");
        sendJson(exchange, 200, JsonRpc.success(id, result), session);
    }

    private JsonElement dispatch(String method, JsonObject params) {
        // 工具列出与调用在这里分流；不知道的方法明确返回“不支持”。
        return switch (method) {
            case "ping" -> new JsonObject();
            case "tools/list" -> {
                JsonObject result = new JsonObject();
                result.add("tools", ToolCatalog.definitions());
                yield result;
            }
            case "tools/call" -> callTool(params);
            default -> throw new JsonRpc.RpcException(-32601, "Method not found");
        };
    }

    private JsonObject callTool(JsonObject params) {
        // 真实的参数校验与游戏侧执行接入后在这里转交；目前五个工具都还没有行为，明确说明尚未接入。
        JsonRpc.only(params, "name", "arguments", "_meta");
        JsonRpc.optionalMeta(params);
        String name = JsonRpc.requiredString(params, "name");
        if (!ToolCatalog.contains(name)) throw new JsonRpc.RpcException(-32602, "Unknown tool");
        return ToolCatalog.notReadyResult(name);
    }

    private void handleDelete(HttpExchange exchange) throws IOException {
        // 删除的是这个 MCP 连接，不影响游戏里正在进行的事。
        McpSession session = requireSession(exchange);
        if (session == null || !validVersionHeader(exchange, session)) return;
        sessions.remove(session.id, session);
        session.close();
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private McpSession createSession(String negotiatedVersion) {
        // 每次初始化创建新连接编号；顺便清理过期连接，并保持连接总数有上限。
        reapSessions();
        McpSession created = new McpSession(UUID.randomUUID().toString(), negotiatedVersion);
        sessions.put(created.id, created);
        trimSessions();
        return created;
    }

    private void reapSessionsSafely() {
        try {
            reapSessions();
        } catch (RuntimeException ignored) {
            // 维护操作尽力执行；单个格式错误或过期会话不能取消周期性回收器。
        }
    }

    /** 关闭空闲超过时限的会话，并把总数压回上限；测试直接调它，不等三十秒的周期。 */
    void reapSessions() {
        long now = System.nanoTime();
        sessions.forEach((id, session) -> {
            if (session.expired(now, sessionTtlNanos) && sessions.remove(id, session)) session.close();
        });
        trimSessions();
    }

    /** 超过上限时，关闭最久没活动的那些连接，不因连接一直增长而无限占用资源。 */
    private void trimSessions() {
        int overflow = sessions.size() - MAX_SESSIONS;
        if (overflow <= 0) return;
        List<McpSession> oldest = new ArrayList<>(sessions.values());
        oldest.sort(Comparator.comparingLong(McpSession::lastActivityNanos));
        for (McpSession session : oldest) {
            if (overflow-- <= 0) break;
            if (sessions.remove(session.id, session)) session.close();
        }
    }

    private McpSession requireSession(HttpExchange exchange) throws IOException {
        // 普通请求必须带初始化得到的连接编号；过期编号要重新初始化，不能当新连接直接使用。
        String id = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
        if (id == null || id.isBlank()) {
            sendStatus(exchange, 400, "MCP session header is required");
            return null;
        }
        McpSession session = sessions.get(id);
        if (session == null || session.closed()) {
            sendStatus(exchange, 404, "Unknown or expired MCP session");
            return null;
        }
        session.touch();
        return session;
    }

    private boolean validVersionHeader(HttpExchange exchange, McpSession session) throws IOException {
        // 已写明版本时必须与初始化协商的一致；未写时按最早的兼容版本处理。
        String supplied = exchange.getRequestHeaders().getFirst(VERSION_HEADER);
        String effective = supplied == null ? OLDEST_SUPPORTED_VERSION : supplied;
        if (!SUPPORTED_VERSIONS.contains(effective)) {
            sendStatus(exchange, 400, "Unsupported MCP protocol version");
            return false;
        }
        if (supplied != null && !session.version.equals(supplied)) {
            sendStatus(exchange, 400, "MCP protocol version does not match the session");
            return false;
        }
        return true;
    }

    private String readBody(HttpExchange exchange) throws IOException {
        // 先看声明长度，再在读取过程中累计真实长度；不能只信请求头，否则分块请求可以绕过大小限制。
        String lengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
        if (lengthHeader != null) {
            try {
                if (Long.parseLong(lengthHeader) > config.maxRequestBytes()) {
                    throw new PayloadTooLargeException("MCP request body is too large");
                }
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid Content-Length", exception);
            }
        }
        try (InputStream input = exchange.getRequestBody();
             ByteArrayOutputStream output = new ByteArrayOutputStream(
                     (int) Math.min(config.maxRequestBytes(), 16_384))) {
            byte[] buffer = new byte[8_192];
            // 大请求也按实际收到的字节累计；用 long 避免接近可配置上限时加法绕回。
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > config.maxRequestBytes()) {
                    throw new PayloadTooLargeException("MCP request body is too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private boolean authenticated(String authorization) {
        // 配置了口令才检查 Bearer；默认空口令表示不启用这一项检查。
        if (config.bearerToken().isBlank()) return true;
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        byte[] expected = config.bearerToken().getBytes(StandardCharsets.UTF_8);
        byte[] supplied = authorization.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, supplied);
    }

    private boolean validOrigin(String origin) {
        // 浏览器带来源时只允许本机的三个常用地址；没有来源头的普通 MCP 客户端继续走其他检查。
        if (origin == null || origin.isBlank()) return true;
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return false;
            }
            String literal = host.toLowerCase(Locale.ROOT);
            if (literal.length() > 2 && literal.charAt(0) == '['
                    && literal.charAt(literal.length() - 1) == ']') {
                literal = literal.substring(1, literal.length() - 1);
            }
            return "localhost".equals(literal)
                    || "127.0.0.1".equals(literal)
                    || "::1".equals(literal);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void installShutdownHook() {
        Thread hook = new Thread(this::stop, "maicraft-mcp-shutdown");
        hook.setDaemon(true);
        Runtime.getRuntime().addShutdownHook(hook);
        shutdownHook = hook;
    }

    private void removeShutdownHook() {
        Thread hook = shutdownHook;
        shutdownHook = null;
        if (hook == null || hook == Thread.currentThread()) return;
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // 虚拟机已进入关闭流程，会正常完成此钩子。
        }
    }

    private static void sendJson(HttpExchange exchange, int status, JsonObject body, McpSession session)
            throws IOException {
        byte[] bytes = JsonRpc.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        if (session != null) {
            headers.set(SESSION_HEADER, session.id);
            headers.set(VERSION_HEADER, session.version);
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private static void sendStatus(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private static void sendStatusSafely(HttpExchange exchange, int status, String message) {
        try {
            sendStatus(exchange, status, message);
        } catch (IOException ignored) {
            exchange.close();
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    /** 请求体超过配置上限；在读取过程中就拒绝，不把超限内容读进内存。 */
    private static final class PayloadTooLargeException extends IOException {
        private PayloadTooLargeException(String message) {
            super(message);
        }
    }
}
