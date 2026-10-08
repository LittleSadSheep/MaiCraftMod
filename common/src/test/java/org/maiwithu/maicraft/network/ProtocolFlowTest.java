// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 协议引擎的离线场景：握手、请求、按身份查询与重放保护，全部用假的对端驱动。 */
class ProtocolFlowTest {

    /** 假对端：固定维度与刻号，登记一个只读操作和一个写操作。 */
    private static final class FakePeer implements ServerProtocolDispatcher.Peer {
        final AtomicLong clock = new AtomicLong(100);
        boolean mayMutate = true;

        @Override public String dimension() { return "minecraft:overworld"; }
        @Override public long tick() { return clock.get(); }
        @Override public boolean mayMutate() { return mayMutate; }
        @Override public List<ServerFeature> features() {
            return List.of(new ServerFeature("ownership.query", 1, false, true, new JsonObject()),
                    new ServerFeature("test.place", 1, true, true, new JsonObject()));
        }
        @Override public JsonObject execute(String operationId, JsonObject body) {
            JsonObject result = new JsonObject();
            result.addProperty("handled", operationId);
            return result;
        }
    }

    private static JsonObject hello() {
        JsonObject request = new JsonObject();
        request.addProperty("kind", "hello");
        request.addProperty("bootstrap", 1);
        request.addProperty("clientNonce", "nonce-1");
        request.addProperty("controlGeneration", 0);
        JsonObject versions = new JsonObject();
        versions.addProperty("ownership.query", 1);
        versions.addProperty("test.place", 1);
        request.add("features", versions);
        return request;
    }

    @Test
    void 握手之后能执行协商过的只读操作并按身份查询() {
        var peer = new FakePeer();
        var dispatcher = new ServerProtocolDispatcher();
        JsonObject welcome = dispatcher.receive(hello(), peer);
        assertEquals("welcome", welcome.get("kind").getAsString());
        assertEquals("succeeded", welcome.get("status").getAsString());
        assertTrue(welcome.getAsJsonObject("features").has("ownership.query"));
        String sessionId = welcome.get("sessionId").getAsString();

        JsonObject request = new JsonObject();
        request.addProperty("kind", "request");
        request.addProperty("bootstrap", 1);
        request.addProperty("sessionId", sessionId);
        request.addProperty("dimension", "minecraft:overworld");
        request.addProperty("requestId", "req-1");
        request.addProperty("operationId", "ownership.query");
        request.addProperty("version", 1);
        request.addProperty("controlGeneration", 0);
        JsonObject body = new JsonObject();
        body.addProperty("probe", "a");
        request.add("body", body);

        JsonObject reply = dispatcher.receive(request, peer);
        assertEquals("succeeded", reply.get("status").getAsString());
        assertEquals("not_applied", reply.get("effect").getAsString());
        assertEquals("ownership.query", reply.getAsJsonObject("result").get("handled").getAsString());

        // 换到未协商的操作要被拒绝，并且这种拒绝说明请求没有执行。
        request.addProperty("operationId", "not.negotiated");
        JsonObject rejected = dispatcher.receive(request, peer);
        assertEquals("rejected", rejected.get("status").getAsString());
    }

    @Test
    void 同一请求身份换了内容会被判为冲突而不是重放() {
        var peer = new FakePeer();
        var dispatcher = new ServerProtocolDispatcher();
        String sessionId = dispatcher.receive(hello(), peer).get("sessionId").getAsString();

        JsonObject request = new JsonObject();
        request.addProperty("kind", "request");
        request.addProperty("bootstrap", 1);
        request.addProperty("sessionId", sessionId);
        request.addProperty("dimension", "minecraft:overworld");
        request.addProperty("requestId", "req-1");
        request.addProperty("operationId", "ownership.query");
        request.addProperty("version", 1);
        request.addProperty("controlGeneration", 0);
        JsonObject body = new JsonObject();
        body.addProperty("probe", "a");
        request.add("body", body);
        dispatcher.receive(request, peer);

        // 相同身份、相同内容：直接返回已存的记录，不重复执行。
        JsonObject again = dispatcher.receive(request, peer);
        assertEquals("succeeded", again.get("status").getAsString());

        // 相同身份、不同内容：冲突，且不会当作新请求执行。
        body.addProperty("probe", "b");
        JsonObject conflict = dispatcher.receive(request, peer);
        assertEquals("rejected", conflict.get("status").getAsString());
        assertEquals("request_conflict", conflict.get("code").getAsString());
    }

    @Test
    void 维度不匹配的请求按作用域不符拒绝() {
        var peer = new FakePeer();
        var dispatcher = new ServerProtocolDispatcher();
        String sessionId = dispatcher.receive(hello(), peer).get("sessionId").getAsString();
        JsonObject request = new JsonObject();
        request.addProperty("kind", "query");
        request.addProperty("bootstrap", 1);
        request.addProperty("sessionId", sessionId);
        request.addProperty("dimension", "minecraft:the_nether");
        request.addProperty("requestId", "req-1");
        JsonObject reply = dispatcher.receive(request, peer);
        assertEquals("rejected", reply.get("status").getAsString());
        assertEquals("session_scope_mismatch", reply.get("code").getAsString());
    }

    @Test
    void 新作用域会让旧作用域失去修改权限但仍可查询() {
        var peer = new FakePeer();
        var dispatcher = new ServerProtocolDispatcher();
        String first = dispatcher.receive(hello(), peer).get("sessionId").getAsString();
        JsonObject second = hello();
        second.addProperty("clientNonce", "nonce-2");
        dispatcher.receive(second, peer);
        // 第一个作用域已被整体撤销；再发查询会得到 unknown，旧效果不能重放。
        JsonObject query = new JsonObject();
        query.addProperty("kind", "query");
        query.addProperty("bootstrap", 1);
        query.addProperty("sessionId", first);
        query.addProperty("dimension", "minecraft:overworld");
        query.addProperty("requestId", "req-1");
        JsonObject reply = dispatcher.receive(query, peer);
        assertEquals("unknown", reply.get("status").getAsString());
    }
}
