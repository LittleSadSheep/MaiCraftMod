// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.maiwithu.maicraft.network.ServerFeature;
import org.maiwithu.maicraft.network.ServerProtocolDispatcher;
import static org.maiwithu.maicraft.client.server.ServerRouterTestHarness.check;

/** Exercise the real client router and server protocol together across thousands of requests. */
public final class ServerSessionLongRunTest {
    public static void main(String[] args) {
        var test = new ServerSessionLongRunTest();
        test.run();
        System.out.println("ServerSessionLongRunTest: passed (6000 observations, mutation, cancellation, reconnect)");
    }

    private final ArrayDeque<JsonObject> replies = new ArrayDeque<>();
    private final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
    private final Set<String> mutationIds = new HashSet<>();
    private final Set<String> requestIds = new HashSet<>();
    private ServerProtocolDispatcher server = new ServerProtocolDispatcher();
    private long tick;
    private long connection = 1;
    private int reads;
    private int writes;
    private int hellos;
    private final ServerProtocolDispatcher.Peer peer = new ServerProtocolDispatcher.Peer() {
        public String dimension() { return "minecraft:overworld"; }
        public long tick() { return tick; }
        public boolean mayMutate() { return true; }
        public List<ServerFeature> features() {
            return List.of(new ServerFeature("test.read", 1, false, true, new JsonObject()),
                    new ServerFeature("test.write", 1, true, true, new JsonObject()));
        }
        public JsonObject execute(String operation, JsonObject body) {
            if (operation.equals("test.read")) reads++; else writes++;
            var result = new JsonObject();
            result.addProperty("status", operation.equals("test.read") ? "observed" : "applied");
            result.addProperty("tick", tick);
            result.addProperty("complete", true);
            return result;
        }
    };
    private final ClientRequestRouter router = new ClientRequestRouter(() -> true, envelope -> {
        String kind = envelope.get("kind").getAsString();
        if (kind.equals("hello")) hellos++;
        if (kind.equals("request")) {
            check(requestIds.add(envelope.get("requestId").getAsString()), "no request identity is replayed");
            if (envelope.get("operationId").getAsString().equals("test.write"))
                mutationIds.add(envelope.get("requestId").getAsString());
        }
        replies.add(server.receive(envelope, peer));
        return true;
    }, () -> {}, callbacks::add, (receipt, send) -> { send.run(); return true; });

    private void run() {
        router.register(new ClientOperation("test.read", 1, false, null));
        router.register(new ClientOperation("test.write", 1, true, null));
        router.bind(connection, connection, peer.dimension(), connection, true, tick);
        pump();
        for (int index = 0; index < 6000; index++) {
            tick += 2;
            var read = router.submit("test.read", new JsonObject(), false);
            advance();
            if (read.snapshot().status() == ClientRequestReceipt.Status.QUEUED) { tick++; advance(); }
            check(read.snapshot().status() == ClientRequestReceipt.Status.SUCCEEDED,
                    "observation " + index + " failed across session rollover: " + read.snapshot().code());
            if (index % 1000 == 0) {
                tick++;
                var mutation = router.submit("test.write", new JsonObject(), true);
                advance();
                router.cancel(mutation.id());
                check(mutation.snapshot().effect() == ClientRequestReceipt.Effect.APPLIED,
                        "cancellation never erases an already executed mutation");
                var queued = router.submit("test.write", new JsonObject(), true);
                router.cancel(queued.id());
                tick++;
                advance();
                check(queued.snapshot().effect() == ClientRequestReceipt.Effect.NOT_APPLIED,
                        "cancelled unsent work stays absent from the server");
            }
            if (index == 5500) {
                router.disconnect();
                server = new ServerProtocolDispatcher();
                connection++;
                router.bind(connection, connection, peer.dimension(), connection, true, ++tick);
                pump();
            }
        }
        check(reads == 6000 && writes == 6 && mutationIds.size() == 6 && requestIds.size() == 6006,
                "long-running observations never duplicate a mutation or lose a fresh request");
        check(hellos >= 12, "real maxRequests thresholds rotate bounded scopes repeatedly");
    }

    private void advance() {
        router.observe(tick);
        pump();
        router.dispatch(true);
        pump();
    }

    private void pump() {
        for (int budget = 0; budget < 100; budget++) {
            JsonObject reply = replies.poll();
            if (reply == null) break;
            router.receive(reply, connection);
        }
        check(replies.isEmpty(), "bounded protocol reply drain");
        while (!callbacks.isEmpty()) callbacks.remove().run();
    }
}
