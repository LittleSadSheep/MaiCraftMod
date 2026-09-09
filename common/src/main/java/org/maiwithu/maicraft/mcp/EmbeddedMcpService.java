package org.maiwithu.maicraft.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.maiwithu.maicraft.intent.SemanticContractException;

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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 游戏进程里的 MCP 网络服务：接收请求、检查连接和格式，再把实际工作交给 RuntimeFacade。
 * 一个网络连接不是一个游戏任务；断开连接或停止等回复，不等于取消已经开始的游戏任务。
 */
public final class EmbeddedMcpService implements AutoCloseable {
    public static final URI ATTENTION_URI = URI.create("maicraft://attention");

    private static final Gson GSON = new Gson();
    private static final String ENDPOINT = "/mcp";
    private static final String SESSION_HEADER = "MCP-Session-Id";
    private static final String VERSION_HEADER = "MCP-Protocol-Version";
    private static final String LATEST_VERSION = "2025-11-25";
    private static final Set<String> SUPPORTED_VERSIONS = Set.of(
            LATEST_VERSION, "2025-06-18", "2025-03-26"
    );
    private static final int MAX_SESSIONS = 64;
    private static final long SESSION_TTL_NANOS = Duration.ofMinutes(10).toNanos();
    private static final long SESSION_REAP_SECONDS = 30L;
    private static final long SSE_HEARTBEAT_SECONDS = 15L;

    private final McpConfig config;
    private final RuntimeFacade runtime;
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean stopping = new AtomicBoolean();

    private HttpServer server;
    private ExecutorService executor;
    private ScheduledExecutorService maintenance;
    private AutoCloseable attentionSubscription;
    private Thread shutdownHook;

    public EmbeddedMcpService(McpConfig config, RuntimeFacade runtime) {
        this.config = Objects.requireNonNull(config, "config");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /** 启动本地端口、网络线程和消息订阅；已经启动时不重复开一个服务。 */
    public synchronized void start() throws IOException {
        if (server != null) return;
        stopping.set(false);

        ExecutorService newExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("maicraft-mcp-", 0).factory()
        );
        ScheduledExecutorService newMaintenance = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("maicraft-mcp-maintenance-", 0).factory()
        );
        HttpServer newServer = null;
        AutoCloseable newSubscription = null;
        try {
            // 先在局部变量里准备各部分；任何一步失败都关闭已创建的资源，全部成功后才记为服务已启动。
            newServer = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
            newServer.createContext(ENDPOINT, this::handle);
            newServer.setExecutor(newExecutor);
            newSubscription = runtime.subscribeAttention(ignored -> publishAttentionUpdate());
            newServer.start();
            newMaintenance.scheduleWithFixedDelay(
                    this::reapSessionsSafely,
                    SESSION_REAP_SECONDS,
                    SESSION_REAP_SECONDS,
                    TimeUnit.SECONDS);

            executor = newExecutor;
            maintenance = newMaintenance;
            server = newServer;
            attentionSubscription = newSubscription;
            installShutdownHook();
        } catch (IOException | RuntimeException exception) {
            closeQuietly(newSubscription);
            if (newServer != null) newServer.stop(0);
            newExecutor.shutdownNow();
            newMaintenance.shutdownNow();
            throw exception;
        }
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    /** Returns the actual port, including an ephemeral port selected for port 0. */
    public synchronized int port() {
        if (server == null) return -1;
        return server.getAddress().getPort();
    }

    /** Number of currently live MCP transport sessions, for the local read-only status command. */
    public int sessionCount() {
        return sessions.size();
    }

    /** 停止接收网络请求，关闭消息等待和连接；游戏任务的清理由 ClientRuntime 另外负责。 */
    public synchronized void stop() {
        if (server == null && executor == null && maintenance == null) return;
        stopping.set(true);

        AutoCloseable subscription = attentionSubscription;
        attentionSubscription = null;
        closeQuietly(subscription);

        ScheduledExecutorService currentMaintenance = maintenance;
        maintenance = null;
        if (currentMaintenance != null) currentMaintenance.shutdownNow();

        sessions.values().forEach(Session::close);
        sessions.clear();

        HttpServer currentServer = server;
        server = null;
        if (currentServer != null) currentServer.stop(0);

        ExecutorService currentExecutor = executor;
        executor = null;
        if (currentExecutor != null) {
            currentExecutor.shutdownNow();
        }
        removeShutdownHook();
    }

    @Override
    public void close() {
        stop();
    }

    private void handle(HttpExchange exchange) throws IOException {
        // 先查是否停服、浏览器来源是否允许、口令是否正确，再按 POST／GET／DELETE 处理请求。
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
                case "GET" -> handleGet(exchange);
                case "DELETE" -> handleDelete(exchange);
                default -> {
                    exchange.getResponseHeaders().set("Allow", "POST, GET, DELETE");
                    sendStatus(exchange, 405, "Method not allowed");
                }
            }
        } catch (PayloadTooLargeException exception) {
            sendStatusSafely(exchange, 413, exception.getMessage());
        } catch (Exception exception) {
            org.maiwithu.maicraft.core.Constants.LOG.debug("[maicraft-mcp] Transport request failed", exception);
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
                sendJson(exchange, 200, error(JsonNull.INSTANCE, -32600, "Invalid Request"), null);
                return;
            }
            request = parsed.getAsJsonObject();
        } catch (JsonParseException exception) {
            sendJson(exchange, 200, error(JsonNull.INSTANCE, -32700, "Parse error"), null);
            return;
        }

        JsonElement id = request.has("id") ? request.get("id") : null;
        String method;
        try {
            if (!"2.0".equals(requiredString(request, "jsonrpc"))) {
                throw new IllegalArgumentException("jsonrpc must be 2.0");
            }
            method = requiredString(request, "method");
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 200, error(idOrNull(id), -32600, "Invalid Request"), null);
            return;
        }

        boolean notification = id == null;
        // 没有请求编号的是通知，不返回普通调用结果；初始化完成和取消等待的通知在这里处理。
        if (notification) {
            Session session = requireSession(exchange);
            if (session == null || !validVersionHeader(exchange, session)) return;
            if (!"notifications/initialized".equals(method) && !"notifications/cancelled".equals(method)) {
                // Unknown notifications are intentionally ignored by JSON-RPC.
            }
            if ("notifications/initialized".equals(method)) session.initialized.set(true);
            if ("notifications/cancelled".equals(method) && request.has("params")
                    && request.get("params").isJsonObject()) {
                JsonElement target = request.getAsJsonObject("params").get("requestId");
                PendingAttention pending = target == null ? null : session.attentionCalls.get(GSON.toJson(target));
                if (pending != null) pending.cancel();
            }
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }

        if ("initialize".equals(method)) {
            handleInitialize(exchange, id, request.get("params"));
            return;
        }

        Session session = requireSession(exchange);
        if (session == null) return;
        if (!validVersionHeader(exchange, session)) return;

        JsonObject response;
        try {
            response = success(id, dispatch(session, method, request.get("params"), id));
        } catch (RpcException exception) {
            response = error(id, exception.code, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            response = error(id, -32602, exception.getMessage());
        } catch (Exception exception) {
            org.maiwithu.maicraft.core.Constants.LOG.error("[maicraft-mcp] RPC dispatch failed: {}", method, exception);
            response = error(id, -32603, "Internal error");
        }
        sendJson(exchange, 200, response, session);
    }

    private void handleInitialize(HttpExchange exchange, JsonElement id, JsonElement rawParams) throws IOException {
        // 协商协议版本并分配连接编号，返回四个工具和知识／任务消息资源的使用说明。
        JsonObject params = optionalObject(rawParams);
        String requested = params.has("protocolVersion") && params.get("protocolVersion").isJsonPrimitive()
                ? params.get("protocolVersion").getAsString()
                : LATEST_VERSION;
        String negotiated = SUPPORTED_VERSIONS.contains(requested) ? requested : LATEST_VERSION;

        Session session = createSession(negotiated);

        JsonObject capabilities = new JsonObject();
        JsonObject tools = new JsonObject();
        tools.addProperty("listChanged", false);
        capabilities.add("tools", tools);
        JsonObject resources = new JsonObject();
        resources.addProperty("subscribe", true);
        resources.addProperty("listChanged", false);
        capabilities.add("resources", resources);

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "maicraft");
        serverInfo.addProperty("version", "1.0.0");

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", negotiated);
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);
        result.addProperty("instructions",
                "Use perceive to understand the current situation, plan to compile a goal, " +
                "execute to start it, and task to inspect or control the returned task. " +
                "Attention is the primary execution monitor. After execute or a task control action, pass " +
                "next_attention to perceive and continue using the response's next_attention. It includes " +
                "authoritative task state, decisions and terminal results; routine task/get polling is unnecessary. " +
                "Timeout only ends a wait; continue quietly while running. Handle decisions, pauses and " +
                "runtime/task unavailability rather than waiting forever. Inspect resync_required and current " +
                "task state after lost history or a stream reset. Hosts may subscribe to maicraft://attention " +
                "and read it on updates; notifications themselves do not run the model. " +
                "For block behavior and Ponder tutorials, start with maicraft://knowledge/index. " +
                "Discover metadata using resources/list or perceive(view=knowledge, focus=item ID/name), " +
                "then read one returned URI using resources/read or perceive(view=knowledge, resource_uri=...). " +
                "Reference knowledge is not an execution capability or a live-world observation.");
        sendJson(exchange, 200, success(id, result), session);
    }

    private JsonElement dispatch(
            Session session, String method, JsonElement rawParams, JsonElement requestId) {
        // 工具调用、资源读取和消息订阅在这里分流；不知道的方法明确返回“不支持”。
        return switch (method) {
            case "ping" -> new JsonObject();
            case "tools/list" -> listTools();
            case "tools/call" -> callTool(session, optionalObject(rawParams), requestId);
            case "resources/list" -> listResources(optionalObject(rawParams), "list");
            case "resources/templates/list" -> listResources(optionalObject(rawParams), "templates");
            case "resources/read" -> readResource(optionalObject(rawParams));
            case "resources/subscribe" -> subscribeResource(session, optionalObject(rawParams));
            case "resources/unsubscribe" -> unsubscribeResource(session, optionalObject(rawParams));
            default -> throw new RpcException(-32601, "Method not found");
        };
    }

    private JsonObject listTools() {
        JsonObject result = new JsonObject();
        result.add("tools", PublicToolCatalog.definitions());
        return result;
    }

    private JsonObject callTool(Session session, JsonObject params, JsonElement requestId) {
        // 接口参数合法才转给游戏运行时；等待回复在网络线程完成，不让游戏线程停在这里。
        only(params, "name", "arguments", "_meta");
        optionalMeta(params);
        String name = requiredString(params, "name");
        if (!PublicToolCatalog.contains(name)) throw new RpcException(-32602, "Unknown tool");

        JsonObject arguments;
        try {
            arguments = PublicToolCatalog.validateAndNormalize(name, params.get("arguments"));
        } catch (IllegalArgumentException exception) {
            return toolError("invalid_arguments", message(exception), true, true, null);
        }

        String requestKey = null;
        if (PublicToolCatalog.EXECUTE.equals(name)) {
            // 调用者没给去重编号时，用连接、请求编号和参数生成一个；主动跨连接重试需要保留返回的编号。
            requestKey = nullableString(arguments, "request_key");
            if (requestKey == null) {
                requestKey = automaticRequestKey(session, requestId, arguments);
                arguments.addProperty("request_key", requestKey);
            }
        }

        PendingAttention pending = PublicToolCatalog.PERCEIVE.equals(name)
                && "attention".equals(nullableString(arguments, "view")) ? new PendingAttention() : null;
        // 只登记 Attention 的可取消等待；取消网络等待不应顺手取消玩家正在做的任务。
        String callId = GSON.toJson(requestId);
        if (pending != null && session.attentionCalls.putIfAbsent(callId, pending) != null)
            throw new RpcException(-32600, "Duplicate active attention request id");
        CompletionStage<JsonElement> stage = null;
        try {
            boolean knowledge = PublicToolCatalog.PERCEIVE.equals(name) && "knowledge".equals(nullableString(arguments, "view"));
            stage = switch (name) {
                case PublicToolCatalog.PERCEIVE -> knowledge ? runtime.knowledge(
                        org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary.perceptionRequest(arguments)) : runtime.perceive(arguments);
                case PublicToolCatalog.PLAN -> runtime.plan(arguments);
                case PublicToolCatalog.EXECUTE -> runtime.execute(arguments);
                case PublicToolCatalog.TASK -> runtime.task(arguments);
                default -> throw new IllegalStateException("unreachable tool dispatch");
            };
            if (pending != null) {
                pending.attach(stage);
                if (session.closed.get()) pending.cancel();
            }
            JsonElement value = await(stage, requestTimeout(name, arguments));
            return knowledge && arguments.has("resource_uri") && !arguments.get("resource_uri").isJsonNull()
                    ? knowledgeToolResult(value.getAsJsonObject()) : toolResult(value, false);
        } catch (CancellationException cancelled) {
            return toolError("attention_wait_cancelled", "Attention wait cancelled; the game task is unchanged.",
                    false, true, null);
        } catch (TimeoutException exception) {
            // 回复超时后先问运行时请求是否已开始；已开始的操作不能承诺“什么都没发生”。
            RuntimeFacade.CancellationDisposition cancellation = cancelRuntimeCall(stage);
            boolean mutating = mutatesSemanticState(name, arguments);
            boolean outcomeKnown = !mutating || cancellation
                    == RuntimeFacade.CancellationDisposition.CANCELLED_BEFORE_START
                    || cancellation == RuntimeFacade.CancellationDisposition.CANCELLED_WHILE_WAITING;
            String message = outcomeKnown
                    ? "The request timed out before it could change semantic or game state."
                    : "The request started before the transport timed out, so its outcome is unknown.";
            return toolError("runtime_timeout", message, true, outcomeKnown, requestKey);
        } catch (InterruptedException exception) {
            RuntimeFacade.CancellationDisposition cancellation = cancelRuntimeCall(stage);
            Thread.currentThread().interrupt();
            boolean mutating = mutatesSemanticState(name, arguments);
            boolean outcomeKnown = !mutating || cancellation
                    == RuntimeFacade.CancellationDisposition.CANCELLED_BEFORE_START
                    || cancellation == RuntimeFacade.CancellationDisposition.CANCELLED_WHILE_WAITING;
            return toolError("transport_stopping",
                    outcomeKnown
                            ? "The MCP transport stopped before the request began."
                            : "The transport stopped after the request began; its outcome is unknown.",
                    true, outcomeKnown, requestKey);
        } catch (Exception exception) {
            Throwable failure = unwrap(exception);
            if (failure instanceof SemanticContractException violation) {
                return semanticContractError(violation, requestKey);
            }
            if (!(failure instanceof IllegalArgumentException || failure instanceof IllegalStateException)) {
                org.maiwithu.maicraft.core.Constants.LOG.error("[maicraft-mcp] Runtime call failed: {}", name, failure);
            }
            boolean outcomeKnown = !mutatesSemanticState(name, arguments);
            return toolError("runtime_error", message(failure), true,
                    outcomeKnown, requestKey);
        } finally {
            if (pending != null) session.attentionCalls.remove(callId, pending);
        }
    }

    private JsonObject listResources(JsonObject params, String action) {
        only(params, "cursor", "_meta"); optionalMeta(params);
        JsonObject request = new JsonObject(); request.addProperty("action", action);
        if (params.has("cursor")) request.add("cursor", params.get("cursor"));
        return knowledgeRequest(request);
    }

    private JsonObject readResource(JsonObject params) {
        // Attention 资源读任务消息；其他地址交给知识库。这里只读资源，不启动目标执行。
        only(params, "uri", "_meta");
        optionalMeta(params);
        String uri = requiredString(params, "uri");
        if (!ATTENTION_URI.toString().equals(uri)) {
            JsonObject request = new JsonObject(); request.addProperty("action", "read"); request.addProperty("uri", uri);
            return knowledgeRequest(request);
        }
        CompletionStage<JsonElement> stage = runtime.readAttention();
        try {
            JsonElement snapshot = await(stage, config.requestTimeout());
            JsonObject content = new JsonObject();
            content.addProperty("uri", ATTENTION_URI.toString());
            content.addProperty("mimeType", "application/json");
            content.addProperty("text", GSON.toJson(nonNull(snapshot)));
            JsonArray contents = new JsonArray();
            contents.add(content);
            JsonObject result = new JsonObject();
            result.add("contents", contents);
            return result;
        } catch (TimeoutException exception) {
            cancelRuntimeCall(stage);
            throw new RpcException(-32001, "The game runtime did not respond before the MCP timeout");
        } catch (InterruptedException exception) {
            cancelRuntimeCall(stage);
            Thread.currentThread().interrupt();
            throw new RpcException(-32001, "The MCP transport stopped while reading attention");
        } catch (Exception exception) {
            throw new RpcException(-32603, message(unwrap(exception)));
        }
    }

    private JsonObject subscribeResource(Session session, JsonObject params) {
        only(params, "uri", "_meta");
        optionalMeta(params);
        requireAttentionUri(params);
        session.attentionSubscribed.set(true);
        session.enqueue(attentionNotification());
        return new JsonObject();
    }

    private JsonObject knowledgeRequest(JsonObject request) {
        // 知识库错误保留自己的错误码；超时只撤回读取请求，不推断游戏内的任务失败。
        CompletionStage<JsonElement> stage = null;
        try {
            stage = runtime.knowledge(request);
            return await(stage, config.requestTimeout()).getAsJsonObject();
        } catch (TimeoutException timeout) {
            cancelRuntimeCall(stage); throw new RpcException(-32001, "Knowledge request timed out");
        } catch (InterruptedException interrupted) {
            cancelRuntimeCall(stage); Thread.currentThread().interrupt();
            throw new RpcException(-32001, "Knowledge request interrupted");
        } catch (Exception failure) {
            Throwable cause = unwrap(failure);
            if (cause instanceof org.maiwithu.maicraft.mcp.knowledge.KnowledgeException resource)
                throw new RpcException(resource.code(), resource.getMessage());
            if (cause instanceof IllegalArgumentException) throw new RpcException(-32602, cause.getMessage());
            throw new RpcException(-32603, message(cause));
        }
    }

    private static JsonObject knowledgeToolResult(JsonObject value) {
        // 文档正文直接作为文本内容返回，结构化部分只放地址和格式，避免同一大段正文重复出现两遍。
        JsonArray content = new JsonArray(), metadata = new JsonArray();
        for (JsonElement element : value.getAsJsonArray("contents")) {
            JsonObject document = element.getAsJsonObject(), text = new JsonObject(), info = new JsonObject();
            text.addProperty("type", "text"); text.add("text", document.get("text")); content.add(text);
            info.add("uri", document.get("uri")); info.add("mimeType", document.get("mimeType")); metadata.add(info);
        }
        JsonObject structured = new JsonObject(); structured.add("resources", metadata);
        JsonObject result = new JsonObject(); result.add("content", content); result.add("structuredContent", structured);
        result.addProperty("isError", false); return result;
    }

    private JsonObject unsubscribeResource(Session session, JsonObject params) {
        only(params, "uri", "_meta");
        optionalMeta(params);
        requireAttentionUri(params);
        session.attentionSubscribed.set(false);
        return new JsonObject();
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        // GET 建立持续消息连接；这里只发“资源更新了”的通知，真正的任务状态仍要读取 Attention。
        String accept = exchange.getRequestHeaders().getFirst("Accept");
        if (accept == null || !accept.contains("text/event-stream")) {
            exchange.getResponseHeaders().set("Allow", "POST, DELETE");
            sendStatus(exchange, 405, "GET requires Accept: text/event-stream");
            return;
        }
        Session session = requireSession(exchange);
        if (session == null || !validVersionHeader(exchange, session)) return;

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream");
        headers.set("Cache-Control", "no-cache, no-transform");
        headers.set("Connection", "keep-alive");
        headers.set(SESSION_HEADER, session.id);
        headers.set(VERSION_HEADER, session.version);
        exchange.sendResponseHeaders(200, 0);

        SseConnection connection = new SseConnection(exchange, session::touch);
        session.attach(connection);
        // Catch up after subscribing before GET, or after events occurred while SSE was disconnected.
        if (session.attentionSubscribed.get()) connection.enqueue(attentionNotification());
        try {
            connection.writeLoop();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            session.detach(connection);
            connection.close();
        }
    }

    private void handleDelete(HttpExchange exchange) throws IOException {
        // 删除的是这个 MCP 连接及其等待请求，不是游戏里的任务记录。
        Session session = requireSession(exchange);
        if (session == null || !validVersionHeader(exchange, session)) return;
        sessions.remove(session.id, session);
        session.close();
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private void publishAttentionUpdate() {
        // 游戏线程只把通知放进队列，不能在这里向慢网络连接写数据，否则会拖住游戏更新。
        JsonObject notification = attentionNotification();
        sessions.values().forEach(session -> {
            // Never perform socket work on the Minecraft publication thread.
            if (session.attentionSubscribed.get()) session.enqueue(notification);
        });
    }

    private static JsonObject attentionNotification() {
        JsonObject params = new JsonObject();
        params.addProperty("uri", ATTENTION_URI.toString());
        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", "2.0");
        notification.addProperty("method", "notifications/resources/updated");
        notification.add("params", params);

        return notification;
    }

    private Session createSession(String negotiatedVersion) {
        // 每次初始化创建新连接编号；顺便清理过期连接，并保持连接总数有上限。
        reapSessionsSafely();
        Session created = new Session(UUID.randomUUID().toString(), negotiatedVersion);
        sessions.put(created.id, created);
        trimSessions();
        return created;
    }

    private void reapSessionsSafely() {
        try {
            long now = System.nanoTime();
            sessions.forEach((id, session) -> {
                if (session.expired(now) && sessions.remove(id, session)) session.close();
            });
            trimSessions();
        } catch (RuntimeException ignored) {
            // Maintenance is best effort; one malformed/stale session must not
            // cancel the periodic reaper.
        }
    }

    private void trimSessions() {
        // 超过六十四个连接时，关闭最久没活动的那些；不会因为连接一直增长而无限占用资源。
        int overflow = sessions.size() - MAX_SESSIONS;
        if (overflow <= 0) return;
        List<Session> oldest = new ArrayList<>(sessions.values());
        oldest.sort(Comparator.comparingLong(Session::lastActivityNanos));
        for (Session session : oldest) {
            if (overflow-- <= 0) break;
            if (sessions.remove(session.id, session)) session.close();
        }
    }

    private Session requireSession(HttpExchange exchange) throws IOException {
        // 普通请求必须带初始化得到的连接编号；过期编号要重新初始化，不能当新连接直接使用。
        String id = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
        if (id == null || id.isBlank()) {
            sendStatus(exchange, 400, "MCP session header is required");
            return null;
        }
        Session session = sessions.get(id);
        if (session == null || session.closed.get()) {
            sendStatus(exchange, 404, "Unknown or expired MCP session");
            return null;
        }
        session.touch();
        return session;
    }

    private boolean validVersionHeader(HttpExchange exchange, Session session) throws IOException {
        // 已写明版本时必须与初始化协商的一致；未写时按旧版本兼容处理。
        String supplied = exchange.getRequestHeaders().getFirst(VERSION_HEADER);
        String effective = supplied == null ? "2025-03-26" : supplied;
        if (!SUPPORTED_VERSIONS.contains(effective)) {
            sendStatus(exchange, 400, "Unsupported MCP protocol version");
            return false;
        }
        // A missing header has the compatibility meaning defined by the transport.
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
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(config.maxRequestBytes(), 16_384))) {
            byte[] buffer = new byte[8_192];
            int total = 0;
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
        } catch (Exception exception) {
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
            // The JVM is already shutting down and will finish this hook normally.
        }
    }

    private static JsonObject toolResult(JsonElement value, boolean isError) {
        // 普通工具结果同时提供 JSON 文本和结构化对象，兼容两种读取方式；错误标记由调用方给出。
        JsonElement payload = nonNull(value);
        String text = GSON.toJson(payload);
        JsonObject contentItem = new JsonObject();
        contentItem.addProperty("type", "text");
        contentItem.addProperty("text", text);
        JsonArray content = new JsonArray();
        content.add(contentItem);

        JsonObject result = new JsonObject();
        result.add("content", content);
        JsonObject structured = payload.isJsonObject() ? payload.getAsJsonObject() : new JsonObject();
        if (!payload.isJsonObject()) structured.add("value", payload);
        result.add("structuredContent", structured);
        result.addProperty("isError", isError);
        return result;
    }

    private static JsonObject toolError(
            String code, String message, boolean retryable, boolean outcomeKnown, String requestKey) {
        return toolError(code, message, retryable, outcomeKnown, requestKey, null);
    }

    private static JsonObject toolError(
            String code, String message, boolean retryable, boolean outcomeKnown,
            String requestKey, JsonObject details) {
        // 分清“能否重试”和“知不知道上次结果”；结果不明时优先建议按 request_key 查原任务。
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        error.addProperty("retryable", retryable);
        error.addProperty("outcome_known", outcomeKnown);
        if (requestKey != null) error.addProperty("request_key", requestKey);
        if (details != null) {
            details.entrySet().forEach(entry ->
                    error.add(entry.getKey(), entry.getValue().deepCopy()));
        }

        JsonArray suggestions = new JsonArray();
        if (!outcomeKnown) {
            if (requestKey != null) {
                JsonObject query = new JsonObject();
                query.addProperty("tool", PublicToolCatalog.TASK);
                JsonObject arguments = new JsonObject();
                arguments.addProperty("action", "list");
                arguments.addProperty("request_key", requestKey);
                query.add("arguments", arguments);
                suggestions.add(query);
            } else {
                suggestions.add("Query current task and world state before deciding whether to retry.");
            }
        } else if (retryable) {
            suggestions.add("Re-observe current facts, then retry with the same request_key when present.");
        }
        error.add("suggested_actions", suggestions);

        JsonObject payload = new JsonObject();
        payload.addProperty("success", false);
        payload.add("error", error);
        return toolResult(payload, true);
    }

    private static JsonObject semanticContractError(
            SemanticContractException violation, String requestKey) {
        JsonObject details = new JsonObject();
        details.addProperty("violation", violation.violationCode());
        details.addProperty("path", violation.path());
        details.addProperty("ability", violation.ability());
        return toolError("invalid_semantic_goal", violation.getMessage(),
                true, true, requestKey, details);
    }

    private Duration requestTimeout(String toolName, JsonObject arguments) {
        // Attention 本身可能要等三十秒，网络超时要加上这段主动等待时间，不能十五秒就先掐断它。
        Duration base = config.requestTimeout();
        if (PublicToolCatalog.PERCEIVE.equals(toolName)
                && "attention".equals(nullableString(arguments, "view"))
                && arguments.has("wait_ms")) {
            return base.plusMillis(arguments.get("wait_ms").getAsLong());
        }
        return base;
    }

    private static boolean mutatesSemanticState(String toolName, JsonObject arguments) {
        if (PublicToolCatalog.PERCEIVE.equals(toolName)) return false;
        if (PublicToolCatalog.PLAN.equals(toolName) || PublicToolCatalog.EXECUTE.equals(toolName)) {
            return true;
        }
        if (!PublicToolCatalog.TASK.equals(toolName)) return false;
        String action = nullableString(arguments, "action");
        return !("get".equals(action) || "list".equals(action));
    }

    private static RuntimeFacade.CancellationDisposition cancelRuntimeCall(
            CompletionStage<JsonElement> stage) {
        // 优先使用运行时提供的准确撤回状态；没有此接口时只能按普通 Future 的取消结果判断。
        if (stage == null) return RuntimeFacade.CancellationDisposition.SETTLED;
        if (stage instanceof RuntimeFacade.ManagedCall managed) return managed.cancelCall();
        return stage.toCompletableFuture().cancel(false)
                ? RuntimeFacade.CancellationDisposition.CANCELLED_BEFORE_START
                : RuntimeFacade.CancellationDisposition.ALREADY_STARTED;
    }

    private static String automaticRequestKey(
            Session session, JsonElement requestId, JsonObject normalizedArguments) {
        // 对连接编号、请求编号和当前 JSON 文本取哈希；这里不是按“语义等价”判断两份参数是否相同。
        JsonObject semanticArguments = normalizedArguments.deepCopy();
        semanticArguments.remove("request_key");
        String material = session.id + "\n" + GSON.toJson(idOrNull(requestId))
                + "\n" + GSON.toJson(semanticArguments);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return "mcp-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static JsonElement await(CompletionStage<JsonElement> stage, Duration timeout) throws Exception {
        if (stage == null) throw new IllegalStateException("runtime returned no completion stage");
        return nonNull(stage.toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS));
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String message(Throwable throwable) {
        String value = throwable == null ? null : throwable.getMessage();
        return value == null || value.isBlank() ? "Game runtime call failed" : value;
    }

    private static JsonElement nonNull(JsonElement element) {
        return element == null ? JsonNull.INSTANCE : element;
    }

    private static JsonObject optionalObject(JsonElement element) {
        if (element == null || element.isJsonNull()) return new JsonObject();
        if (!element.isJsonObject()) throw new IllegalArgumentException("params must be an object");
        return element.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return value.getAsString();
    }

    private static String nullableString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static void optionalMeta(JsonObject params) {
        JsonElement meta = params.get("_meta");
        if (meta != null && !meta.isJsonNull() && !meta.isJsonObject()) {
            throw new IllegalArgumentException("_meta must be an object");
        }
    }

    private static void only(JsonObject object, String... names) {
        Set<String> allowed = Set.of(names);
        object.keySet().forEach(name -> {
            if (!allowed.contains(name)) throw new IllegalArgumentException("unexpected parameter: " + name);
        });
    }

    private static void requireAttentionUri(JsonObject params) {
        if (!ATTENTION_URI.toString().equals(requiredString(params, "uri"))) {
            if (requiredString(params, "uri").startsWith("maicraft://knowledge/"))
                throw new RpcException(-32602, "Knowledge is read on demand; resource update subscriptions are supported for maicraft://attention only");
            throw new RpcException(-32002, "Resource not found");
        }
    }

    private static JsonObject success(JsonElement id, JsonElement result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", idOrNull(id));
        response.add("result", nonNull(result));
        return response;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", idOrNull(id));
        response.add("error", body);
        return response;
    }

    private static JsonElement idOrNull(JsonElement id) {
        return id == null ? JsonNull.INSTANCE : id;
    }

    private static void sendJson(HttpExchange exchange, int status, JsonObject body, Session session) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
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

    private static final class PendingAttention {
        // 取消通知可能比实际等待对象更早到达，先记下 cancelled，等对象装上来再补取消。
        private CompletionStage<JsonElement> stage;
        private boolean cancelled;
        synchronized void attach(CompletionStage<JsonElement> next) {
            stage = next;
            if (cancelled) cancelRuntimeCall(stage);
        }
        synchronized void cancel() {
            cancelled = true;
            if (stage != null) cancelRuntimeCall(stage);
        }
    }

    private static final class Session {
        // 保存一个 MCP 客户端连接的版本、消息订阅和活动时间，不保存玩家的业务目标。
        private final String id;
        private final String version;
        private final AtomicBoolean attentionSubscribed = new AtomicBoolean();
        private final AtomicBoolean initialized = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<SseConnection> connection = new AtomicReference<>();
        private final ConcurrentMap<String, PendingAttention> attentionCalls = new ConcurrentHashMap<>();
        private volatile long lastActivityNanos = System.nanoTime();

        private Session(String id, String version) {
            this.id = id;
            this.version = version;
        }

        private void touch() {
            lastActivityNanos = System.nanoTime();
        }

        private long lastActivityNanos() {
            return lastActivityNanos;
        }

        private boolean expired(long nowNanos) {
            return closed.get() || nowNanos - lastActivityNanos > SESSION_TTL_NANOS;
        }

        private void attach(SseConnection next) {
            // 同一连接只保留一个消息输出通道；新通道接上时关闭旧通道，断线与替换竞争时再复查一次。
            touch();
            if (closed.get()) {
                next.close();
                return;
            }
            SseConnection previous = connection.getAndSet(next);
            if (previous != null) previous.close();

            // close() can race between the first check and getAndSet().  The
            // second check prevents resurrecting an exchange on an expired
            // session without taking a monitor that stop() would have to wait for.
            if (closed.get() && connection.compareAndSet(next, null)) next.close();
        }

        private void detach(SseConnection candidate) {
            connection.compareAndSet(candidate, null);
        }

        private boolean enqueue(JsonObject message) {
            SseConnection current = connection.get();
            return current != null && current.enqueue(message);
        }

        private void close() {
            // 只关闭一次，并取消还在等消息的请求；已经提交到游戏里的普通 execute 任务不在此列表中。
            if (!closed.compareAndSet(false, true)) return;
            attentionCalls.values().forEach(PendingAttention::cancel);
            attentionCalls.clear();
            SseConnection current = connection.getAndSet(null);
            if (current != null) current.close();
        }
    }

    private static final class SseConnection implements AutoCloseable {
        // 只留一个待发“有更新”通知就够了；客户端收到后会读取最新状态，不需要缓存每条提醒。
        private final HttpExchange exchange;
        private final OutputStream output;
        private final Runnable activity;
        private final ArrayBlockingQueue<Outbound> outbound = new ArrayBlockingQueue<>(1);
        private final AtomicBoolean done = new AtomicBoolean();

        private SseConnection(HttpExchange exchange, Runnable activity) throws IOException {
            this.exchange = exchange;
            this.output = exchange.getResponseBody();
            this.activity = activity;
        }

        private boolean enqueue(JsonObject message) {
            if (done.get()) return false;
            Outbound update = new Outbound(message.deepCopy(), false);
            if (outbound.offer(update)) return true;

            // Resource-updated messages are level-triggered: one pending signal
            // already tells the client to read the latest attention snapshot.
            // Never wait for a slow reader and never perform socket work here.
            return !done.get();
        }

        private void writeLoop() throws InterruptedException {
            // 有通知就发通知，没有则每十五秒发心跳维持连接；网络错误只结束这条消息通道。
            try {
                writeComment("connected");
                while (!done.get()) {
                    Outbound next = outbound.poll(SSE_HEARTBEAT_SECONDS, TimeUnit.SECONDS);
                    if (next == null) {
                        writeComment("heartbeat");
                    } else if (next.terminal()) {
                        return;
                    } else {
                        writeMessage(next.message());
                    }
                }
            } catch (IOException ignored) {
                // A disconnected or non-reading client is isolated to this GET
                // handler.  Its session is detached in handleGet's finally block.
            }
        }

        private void writeMessage(JsonObject message) throws IOException {
            if (done.get()) return;
            byte[] bytes = ("event: message\ndata: " + GSON.toJson(message) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            output.write(bytes);
            output.flush();
            activity.run();
        }

        private void writeComment(String text) throws IOException {
            if (done.get()) return;
            output.write((": " + text + "\n\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            activity.run();
        }

        @Override
        public void close() {
            if (!done.compareAndSet(false, true)) return;
            outbound.clear();
            outbound.offer(Outbound.CLOSE);
            // No monitor is acquired and no writer completion is awaited.  This
            // makes session replacement, expiry, and stop() bounded even when a
            // client has stopped reading its SSE stream.
            exchange.close();
        }

        private record Outbound(JsonObject message, boolean terminal) {
            private static final Outbound CLOSE = new Outbound(null, true);
        }
    }

    private static final class RpcException extends RuntimeException {
        private final int code;

        private RpcException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final class PayloadTooLargeException extends IOException {
        private PayloadTooLargeException(String message) {
            super(message);
        }
    }
}
