// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.serverlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 客户端请求路由的离线场景：握手、只读请求的分发与结果、服务端未确认时不下发。
 * 发送通道用列表捕获，应答信封按服务端协议手工构造。
 */
class RequestRouterTest {

    /** 捕获发出的信封；available 可切换，模拟通道协商前后的状态。 */
    private static final class CapturingTransport implements LinkTransport {
        boolean available;
        final List<JsonObject> sent = new ArrayList<>();

        @Override public boolean available() { return available; }
        @Override public void send(JsonObject envelope) { sent.add(envelope.deepCopy()); }

        JsonObject last() { return sent.get(sent.size() - 1); }
    }

    private static JsonObject welcome(String clientNonce, String sessionId) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("kind", "welcome");
        envelope.addProperty("bootstrap", 1);
        envelope.addProperty("status", "succeeded");
        envelope.addProperty("clientNonce", clientNonce);
        envelope.addProperty("sessionId", sessionId);
        envelope.addProperty("dimension", "minecraft:overworld");
        envelope.addProperty("controlGeneration", 0);
        envelope.addProperty("allowed", true);
        envelope.addProperty("serverTick", 100);
        JsonObject features = new JsonObject();
        JsonObject query = new JsonObject();
        query.addProperty("version", 1);
        query.addProperty("mutating", false);
        query.addProperty("enabled", true);
        query.add("limits", new JsonObject());
        features.add("ownership.query", query);
        envelope.add("features", features);
        JsonObject limits = new JsonObject();
        limits.addProperty("retentionTicks", 6000);
        limits.addProperty("maxRequests", 512);
        limits.addProperty("maxRequestsPerTick", 8);
        envelope.add("limits", limits);
        return envelope;
    }

    private static JsonObject receipt(String sessionId, String requestId) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("kind", "receipt");
        envelope.addProperty("bootstrap", 1);
        envelope.addProperty("sessionId", sessionId);
        envelope.addProperty("dimension", "minecraft:overworld");
        envelope.addProperty("requestId", requestId);
        envelope.addProperty("operationId", "ownership.query");
        envelope.addProperty("version", 1);
        envelope.addProperty("status", "succeeded");
        envelope.addProperty("effect", "not_applied");
        envelope.addProperty("serverTick", 101);
        envelope.add("result", new JsonObject());
        return envelope;
    }

    @Test
    void 服务端确认之前请求留在队列里确认之后才发出() {
        var transport = new CapturingTransport();
        transport.available = true;
                var router = new RequestRouter(transport::available, envelope -> { transport.send(envelope); return true; },
                () -> { }, Runnable::run, (ClientRequest request, Runnable send) -> true);
        var session = router.session();
        router.register(new ClientOperation("ownership.query", 1, false));
        router.bind(1, 1, "minecraft:overworld", 0, true, 0);
        router.observe(0);
        // 绑定后立即发出 hello。
        assertEquals("hello", transport.last().get("kind").getAsString());

        var request = router.submit("ownership.query", new JsonObject(), false);
        router.dispatch(true);
        // 服务器还没确认支持，连只读请求也不下发。
        assertEquals(ClientRequest.Status.QUEUED, router.poll(request.id()).orElseThrow().status());

        // 用捕获到的 nonce 回一个欢迎包；随后确认生效，请求可以发出。
        router.receive(welcome(session.nonce, "session-1"), 1);
        assertTrue(router.serverConfirmed());
        router.dispatch(true);
        assertEquals("request", transport.last().get("kind").getAsString());
        assertEquals("ownership.query", transport.last().get("operationId").getAsString());
        assertEquals("session-1", transport.last().get("sessionId").getAsString());

        // 服务端按请求身份回结果：请求结算成功，只读操作的效果是"未应用"。
        router.receive(receipt("session-1", request.id().toString()), 1);
        var snapshot = router.poll(request.id()).orElseThrow();
        assertEquals(ClientRequest.Status.SUCCEEDED, snapshot.status());
        assertEquals(ClientRequest.Effect.NOT_APPLIED, snapshot.effect());
        assertFalse(snapshot.unresolvedMutation());
    }

    @Test
    void 欢迎包带的随机数不对时握手不生效() {
        var transport = new CapturingTransport();
        transport.available = true;
                var router = new RequestRouter(transport::available, envelope -> { transport.send(envelope); return true; },
                () -> { }, Runnable::run, (ClientRequest request, Runnable send) -> true);
        var session = router.session();
        router.register(new ClientOperation("ownership.query", 1, false));
        router.bind(1, 1, "minecraft:overworld", 0, true, 0);
        router.observe(0);
        router.receive(welcome("not-our-nonce", "session-1"), 1);
        assertFalse(router.serverConfirmed());
    }

    @Test
    void 通道不可用时请求不能发出() {
        var transport = new CapturingTransport();
        transport.available = false;
                var router = new RequestRouter(transport::available, envelope -> { transport.send(envelope); return true; },
                () -> { }, Runnable::run, (ClientRequest request, Runnable send) -> true);
        var session = router.session();
        router.register(new ClientOperation("ownership.query", 1, false));
        router.bind(1, 1, "minecraft:overworld", 0, true, 0);
        router.observe(0);
        // 通道恢复后才收到欢迎包：握手生效，但发送仍走不可用的通道。
        transport.available = true;
        router.observe(0);
        router.receive(welcome(session.nonce, "session-1"), 1);
        assertTrue(router.serverConfirmed());
        // 通道再次断开时，请求发不出去，仍留在队列里等下一刻。
        transport.available = false;
        int before = transport.sent.size();
        var request = router.submit("ownership.query", new JsonObject(), false);
        router.dispatch(true);
        assertEquals(before, transport.sent.size());
        assertEquals(ClientRequest.Status.QUEUED, router.poll(request.id()).orElseThrow().status());
    }

    @Test
    void 未登记的操作提交直接拒绝() {
        var router = new RequestRouter(() -> true, envelope -> true,
                () -> { }, Runnable::run, (ClientRequest request, Runnable send) -> true);
        assertThrows(IllegalArgumentException.class,
                () -> router.submit("unknown.op", new JsonObject(), false));
    }
}
