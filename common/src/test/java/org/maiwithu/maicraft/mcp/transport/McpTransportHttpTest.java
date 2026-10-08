// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 用真实 HTTP 往返核对传输语义：鉴权与来源检查、会话的建立与过期、协议版本协商、
 * 请求大小上限，以及 JSON-RPC 的错误应答。工具目前只有五个空壳：能列出、调用明确回答尚未接入。
 */
class McpTransportHttpTest {
    private static final int SMALL_BODY_LIMIT = 4 * 1024;

    private static HttpClient client;
    private static EmbeddedMcpService service;
    private static EmbeddedMcpService guarded;     // 配了口令与请求大小上限的实例
    private static String session;
    private static String guardedSession;
    private static URI uri;
    private static URI guardedUri;

    @BeforeAll
    static void start() throws Exception {
        client = HttpClient.newHttpClient();
        service = new EmbeddedMcpService(McpConfig.local(0));
        service.start();
        uri = URI.create("http://127.0.0.1:" + service.port() + "/mcp");

        guarded = new EmbeddedMcpService(new McpConfig(
                "127.0.0.1", 0, "secret-token", SMALL_BODY_LIMIT, Duration.ofSeconds(15)));
        guarded.start();
        guardedUri = URI.create("http://127.0.0.1:" + guarded.port() + "/mcp");

        session = initialize(uri, null, LATEST_VERSION).session();
        guardedSession = initialize(guardedUri, "Bearer secret-token", null).session();
    }

    @AfterAll
    static void stop() {
        service.close();
        guarded.close();
    }

    @Test
    void initializeNegotiatesVersionsAndIssuesASession() throws Exception {
        Response latest = initialize(uri, null, null);
        assertEquals(LATEST_VERSION, latest.result().get("protocolVersion").getAsString(),
                "没写版本请求时按最新受支持版本协商");
        assertEquals("maicraft", latest.result().getAsJsonObject("serverInfo").get("name").getAsString());
        assertNotNull(latest.capabilities().getAsJsonObject("tools"), "能力声明报告工具");
        assertNull(latest.capabilities().get("resources"), "还没有可订阅的资源");

        Response old = initialize(uri, null, "2025-03-26");
        assertEquals("2025-03-26", old.result().get("protocolVersion").getAsString(),
                "受支持的旧版本按请求的版本协商");
        // 协商出的旧版本会话，随后带同一版本号的请求应当通过。
        assertEquals(200, post(old.session(), 9, "ping", new JsonObject(), "2025-03-26").statusCode());

        Response unknown = initialize(uri, null, "1999-01-01");
        assertEquals(LATEST_VERSION, unknown.result().get("protocolVersion").getAsString(),
                "不受支持的版本回落到最新版本");
    }

    @Test
    void toolsListFiveShellsAndCallsAnswerNotReady() throws Exception {
        HttpResponse<String> list = post(session, 20, "tools/list", new JsonObject(), null);
        assertEquals(200, list.statusCode());
        JsonArray tools = result(list).getAsJsonArray("tools");
        Set<String> names = new HashSet<>();
        tools.forEach(tool -> names.add(tool.getAsJsonObject().get("name").getAsString()));
        assertEquals(Set.of("observe", "lookup", "execute", "task", "events"), names,
                "五个工具都能被发现，不多不少");
        for (JsonElement element : tools) {
            JsonObject tool = element.getAsJsonObject();
            assertTrue(tool.has("description") && tool.has("inputSchema"), "每个工具都带用途与参数表");
        }

        JsonObject call = new JsonObject();
        call.addProperty("name", "observe");
        call.add("arguments", new JsonObject());
        HttpResponse<String> invoked = post(session, 21, "tools/call", call, null);
        assertEquals(200, invoked.statusCode());
        JsonObject toolResult = result(invoked);
        assertTrue(toolResult.get("isError").getAsBoolean(), "尚未接入按明确的错误应答");
        String text = toolResult.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        JsonObject payload = JsonParser.parseString(text).getAsJsonObject();
        assertEquals("not_ready", payload.getAsJsonObject("error").get("code").getAsString());

        JsonObject unknown = new JsonObject();
        unknown.addProperty("name", "teleport");
        HttpResponse<String> refused = post(session, 22, "tools/call", unknown, null);
        assertEquals(-32602, error(refused).get("code").getAsInt(), "名单之外的工具按未知参数拒收");
    }

    @Test
    void jsonRpcFramingErrorsUseTheStandardCodes() throws Exception {
        HttpRequest raw = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{not json"))
                .build();
        assertEquals(-32700, error(client.send(raw, HttpResponse.BodyHandlers.ofString())).get("code").getAsInt(),
                "解析失败返回 Parse error");

        HttpRequest notObject = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("[1,2]"))
                .build();
        assertEquals(-32600, error(client.send(notObject, HttpResponse.BodyHandlers.ofString())).get("code").getAsInt());

        assertEquals(-32601, error(post(session, 30, "resources/list", new JsonObject(), null)).get("code").getAsInt(),
                "没有的方法明确返回不支持");

        assertEquals(200, post(session, 31, "ping", new JsonObject(), null).statusCode());
    }

    @Test
    void requestsNeedASessionAndTheSessionHeaderMustBeValid() throws Exception {
        assertEquals(400, post(null, 40, "ping", new JsonObject(), null).statusCode(),
                "缺会话编号的请求被拒收");
        assertEquals(404, post("no-such-session", 41, "ping", new JsonObject(), null).statusCode(),
                "不存在的会话编号按过期处理");
    }

    @Test
    void versionHeaderMustMatchTheNegotiatedVersion() throws Exception {
        assertEquals(400, post(session, 50, "ping", new JsonObject(), "2025-06-18").statusCode(),
                "与初始化协商不一致的版本被拒收");
        assertEquals(400, post(session, 51, "ping", new JsonObject(), "1999-01-01").statusCode(),
                "不受支持的版本被拒收");
        assertEquals(200, post(session, 52, "ping", new JsonObject(), null).statusCode(),
                "未写版本头时按最早的兼容版本放行");
    }

    @Test
    void bearerTokenAndBrowserOriginAreCheckedBeforeAnythingElse() throws Exception {
        assertEquals(401, post(guardedSession, 60, "ping", new JsonObject(), null, guardedUri, null).statusCode(),
                "没带口令的请求被拒收");
        assertEquals(401, post(guardedSession, 61, "ping", new JsonObject(), null, guardedUri,
                "Bearer wrong-token").statusCode(), "错误口令被拒收");
        assertEquals(200, post(guardedSession, 62, "ping", new JsonObject(), null, guardedUri,
                "Bearer secret-token").statusCode());
        // 来源检查在口令之前：来源不合法的浏览器请求先被拒收，不进入口令比对。
        assertEquals(403, request(base(guardedUri, guardedSession)
                .header("Origin", "https://evil.example")
                .header("Authorization", "Bearer nope")
                .POST(HttpRequest.BodyPublishers.ofString(plain(63, "ping"))).build()).statusCode());

        assertEquals(403, request(base(uri, session)
                .header("Origin", "https://evil.example")
                .POST(HttpRequest.BodyPublishers.ofString(plain(64, "ping"))).build()).statusCode(),
                "非本机来源的浏览器请求被拒收");
        assertEquals(200, request(base(uri, session)
                .header("Origin", "http://localhost:5173")
                .POST(HttpRequest.BodyPublishers.ofString(plain(65, "ping"))).build()).statusCode(),
                "本机来源放行");
    }

    @Test
    void oversizedBodiesAreRejectedWhileReading() throws Exception {
        char[] big = new char[SMALL_BODY_LIMIT + 1];
        JsonObject call = new JsonObject();
        call.addProperty("name", "observe");
        call.addProperty("padding", new String(big));
        HttpResponse<String> response = post(guardedSession, 70, "tools/call", call, null, guardedUri, "Bearer secret-token");
        assertEquals(413, response.statusCode(), "超过请求预算的报文被拒收");
    }

    @Test
    void notificationsAreAcceptedWithoutAResult() throws Exception {
        HttpRequest notification = base(uri, session)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .build();
        assertEquals(202, client.send(notification, HttpResponse.BodyHandlers.ofString()).statusCode(),
                "通知不返回调用结果，也不报错");
    }

    @Test
    void deletingASessionEndsIt() throws Exception {
        Response fresh = initialize(uri, null, null);
        assertEquals(204, request(base(uri, fresh.session())
                .DELETE().build()).statusCode());
        assertEquals(404, post(fresh.session(), 80, "ping", new JsonObject(), null).statusCode(),
                "删除后的连接编号不能再使用");
    }

    @Test
    void idleSessionsAreReapedAndTheTotalStaysBounded() throws Exception {
        Response fresh = initialize(uri, null, null);
        long before = service.sessionCount();
        service.reapSessions();
        assertEquals(before, service.sessionCount(), "刚活动的会话不被回收");
        // 删除会话再回收，确认回收器真的在清点。
        service.reapSessions();

        // 超过上限后最久没活动的会话被关闭：新建一批会话，最早的会话应被挤出。
        String oldest = fresh.session();
        for (int i = 0; i < 64; i++) initialize(uri, null, null);
        service.reapSessions();
        assertEquals(404, post(oldest, 81, "ping", new JsonObject(), null).statusCode(),
                "最久没活动的会话在超员时被关闭");
        assertTrue(service.sessionCount() <= 64, "会话总数有上限");
    }

    @Test
    void sessionsIdleBeyondTheTtlExpire() throws Exception {
        EmbeddedMcpService shortLived = new EmbeddedMcpService(McpConfig.local(0), 1);
        try {
            shortLived.start();
            try {
                Response fresh = initialize(
                        URI.create("http://127.0.0.1:" + shortLived.port() + "/mcp"), null, null);
                Thread.sleep(5);
                shortLived.reapSessions();
                assertEquals(0, shortLived.sessionCount(), "超过时限的空闲会话被回收");
                URI endpoint = URI.create("http://127.0.0.1:" + shortLived.port() + "/mcp");
                assertEquals(404, post(fresh.session(), 82, "ping", new JsonObject(), null, endpoint, null).statusCode());
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        } finally {
            shortLived.close();
        }
    }

    // ---- HTTP 往返的小工具 ----

    private static final String LATEST_VERSION = "2025-11-25";

    private record Response(HttpResponse<String> raw, JsonObject result, JsonObject capabilities, String session) {}

    private static Response initialize(URI endpoint, String bearer, String protocolVersion) throws Exception {
        JsonObject params = new JsonObject();
        if (protocolVersion != null) params.addProperty("protocolVersion", protocolVersion);
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 1);
        request.addProperty("method", "initialize");
        request.add("params", params);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toString()));
        if (bearer != null) builder.header("Authorization", bearer);
        HttpResponse<String> raw = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, raw.statusCode(), "initialize 应当成功");
        return new Response(raw, result(JsonParser.parseString(raw.body()).getAsJsonObject()),
                result(JsonParser.parseString(raw.body()).getAsJsonObject()).getAsJsonObject("capabilities"),
                raw.headers().firstValue("MCP-Session-Id").orElseThrow());
    }

    private static HttpResponse<String> post(String session, int id, String method, JsonObject params,
                                             String versionHeader) throws Exception {
        return post(session, id, method, params, versionHeader, uri, null);
    }

    private static HttpResponse<String> post(String session, int id, String method, JsonObject params,
                                             String versionHeader, URI endpoint, String bearer) throws Exception {
        HttpRequest.Builder builder = base(endpoint, session);
        if (versionHeader != null) builder.header("MCP-Protocol-Version", versionHeader);
        if (bearer != null) builder.header("Authorization", bearer);
        builder.POST(HttpRequest.BodyPublishers.ofString(plain(id, method, params)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String plain(int id, String method) {
        return plain(id, method, new JsonObject());
    }

    private static String plain(int id, String method, JsonObject params) {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", id);
        request.addProperty("method", method);
        request.add("params", params);
        return request.toString();
    }

    private static HttpRequest.Builder base(URI endpoint, String session) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json");
        if (session != null) builder.header("MCP-Session-Id", session);
        return builder;
    }

    private static HttpResponse<String> request(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject result(HttpResponse<String> response) {
        return result(JsonParser.parseString(response.body()).getAsJsonObject());
    }

    private static JsonObject result(JsonObject body) {
        return body.getAsJsonObject("result");
    }

    private static JsonObject error(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("error");
    }
}
