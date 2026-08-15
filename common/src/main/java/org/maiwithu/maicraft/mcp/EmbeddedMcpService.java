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
 * An in-process Streamable HTTP MCP endpoint backed by one injected runtime.
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

    /** Starts the endpoint once. Repeated calls while running are no-ops. */
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

    /**
     * Stops accepting work without waiting for game-thread calls or running tasks.
     */
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
            sendStatusSafely(exchange, 500, "Internal MCP transport error");
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
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
        if (notification) {
            Session session = requireSession(exchange);
            if (session == null || !validVersionHeader(exchange, session)) return;
            if (!"notifications/initialized".equals(method) && !"notifications/cancelled".equals(method)) {
                // Unknown notifications are intentionally ignored by JSON-RPC.
            }
            if ("notifications/initialized".equals(method)) session.initialized.set(true);
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
            response = error(id, -32603, "Internal error");
        }
        sendJson(exchange, 200, response, session);
    }

    private void handleInitialize(HttpExchange exchange, JsonElement id, JsonElement rawParams) throws IOException {
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
                "Use maicraft_perceive to understand the current situation, maicraft_plan to compile a goal, " +
                "maicraft_execute to start it, and maicraft_task to inspect or control the returned task. " +
                "Execution is asynchronous. Subscribe to maicraft://attention for important events and decisions.");
        sendJson(exchange, 200, success(id, result), session);
    }

