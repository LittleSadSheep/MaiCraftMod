// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import static org.maiwithu.maicraft.client.server.ClientRequestReceipt.*;

final class ServerRouterTestHarness {
    final Thread client = Thread.currentThread();
    final ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
    final List<JsonObject> sent = new ArrayList<>();
    final Local local = new Local();
    final ClientRequestRouter router;
    boolean available;
    boolean throwAfterSend;
    boolean sendReturnsFalse;
    long tick;

    ServerRouterTestHarness(boolean available) {
        this(available, MutationPersistence.NONE);
    }

    ServerRouterTestHarness(boolean available, MutationPersistence persistence) {
        this.available = available;
        router = new ClientRequestRouter(() -> this.available, envelope -> {
            if (sendReturnsFalse) return false;
            sent.add(envelope.deepCopy());
            if (throwAfterSend && kind(envelope).equals("request")) throw new IllegalStateException("lost send acknowledgement");
            return true;
        }, () -> check(Thread.currentThread() == client, "wrong client thread"), callbacks::add,
                (receipt, send) -> { send.run(); return true; }, persistence);
        router.register(new ClientOperation("test.read", 1, false, local));
        router.register(new ClientOperation("test.write", 1, true, local));
        router.register(new ClientOperation("test.remote", 1, true, null));
        router.register(new ClientOperation("test.other", 1, true, local));
        router.bind(1, 1, "minecraft:overworld", 1, true, 0);
    }

    void welcome(String... operations) {
        JsonObject welcome = welcomeEnvelope(operations);
        router.receive(welcome, 1);
        acknowledgeControl();
    }

    JsonObject welcomeEnvelope(String... operations) {
        JsonObject welcome = new JsonObject();
        welcome.addProperty("kind", "welcome");
        welcome.addProperty("bootstrap", 1);
        welcome.addProperty("status", "succeeded");
        welcome.addProperty("clientNonce", last("hello").get("clientNonce").getAsString());
        welcome.addProperty("sessionId", "server-session-" + last("hello").get("clientNonce").getAsString());
        welcome.addProperty("dimension", "minecraft:overworld");
        JsonObject features = new JsonObject();
        for (String operation : operations) {
            JsonObject feature = new JsonObject();
            feature.addProperty("version", 1);
            feature.addProperty("mutating", !operation.equals("test.read"));
            feature.addProperty("enabled", true);
            features.add(operation, feature);
        }
        welcome.add("features", features);
        return welcome;
    }

    void acknowledgeControl() {
        JsonObject ack = last("control").deepCopy();
        ack.addProperty("status", "succeeded");
        router.receive(ack, 1);
    }

    ClientRequestReceipt submit(String operation) {
        return router.submit(operation, new JsonObject(), !operation.equals("test.read"));
    }

    void advance(long targetTick) {
        tick = targetTick;
        router.observe(tick);
        router.dispatch(true);
        drain();
    }

    void drain() {
        for (int i = 0; i < 1000; i++) {
            Runnable callback = callbacks.poll();
            if (callback == null) return;
            callback.run();
        }
        throw new AssertionError("unbounded callback queue");
    }

    JsonObject reply(ClientRequestReceipt receipt, String status, String effect, String code) {
        JsonObject reply = sent.stream().filter(envelope -> kind(envelope).equals("request")
                && envelope.get("requestId").getAsString().equals(receipt.id().toString())).findFirst().orElseThrow().deepCopy();
        reply.remove("body");
        reply.addProperty("kind", "receipt");
        reply.addProperty("status", status);
        reply.addProperty("effect", effect);
        reply.addProperty("code", code);
        reply.addProperty("serverTick", 12);
        return reply;
    }

    JsonObject last(String kind) { return sent.stream().filter(envelope -> kind(envelope).equals(kind)).reduce((a,b) -> b).orElseThrow(); }
    long count(String kind) { return sent.stream().filter(envelope -> kind(envelope).equals(kind)).count(); }
    static String kind(JsonObject envelope) { return envelope.get("kind").getAsString(); }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    static final class Local implements ClientFallback {
        boolean supported = true;
        boolean ready = true;
        boolean async;
        int submissions;
        int cancellations;
        Consumer<Result> completion;
        @Override public boolean supported() { return supported; }
        @Override public Availability availability(JsonObject arguments) {
            return ready ? Availability.ready() : Availability.unavailable("terminal_out_of_reach");
        }
        @Override public void submit(UUID id, JsonObject arguments, Consumer<Result> completion) {
            submissions++;
            this.completion = completion;
            if (!async) completion.accept(new Result(Status.SUCCEEDED, Effect.APPLIED, null, "", "native operation observed"));
        }
        @Override public void cancel(UUID id) { cancellations++; }
    }
}
