// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;

final class ProtocolFixture implements ServerProtocolDispatcher.Peer {
    final ServerProtocolDispatcher dispatcher = new ServerProtocolDispatcher();
    String dimension = "minecraft:overworld";
    long tick = 100;
    int mutations;
    boolean allowed = true;
    boolean disabled;
    String failure = "";
    JsonObject welcome;

    ProtocolFixture() { welcome = send(hello(UUID.randomUUID().toString())); }

    @Override public String dimension() { return dimension; }
    @Override public long tick() { return tick; }
    @Override public boolean mayMutate() { return allowed; }
    @Override public List<ServerFeature> features() {
        return List.of(new ServerFeature("machine.configure", 1, true, !disabled, new JsonObject()),
                new ServerFeature("machine.snapshot", 1, false, true, new JsonObject()));
    }
    @Override public JsonObject execute(String operationId, JsonObject body) {
        if (failure.equals("preflight")) throw ServerOperationException.notApplied("out_of_reach", "Too far");
        if (operationId.equals("machine.configure")) mutations++;
        if (failure.equals("native")) throw new IllegalStateException("Native operation threw after mutation");
        JsonObject result = new JsonObject();
        result.addProperty("count", mutations);
        result.add("body", body);
        if (failure.equals("large")) result.addProperty("large", "x".repeat(ProtocolJson.MAX_ENVELOPE_CHARS));
        if (failure.equals("edge")) result.addProperty("edge", "x".repeat(body.get("size").getAsInt()));
        return result;
    }

    JsonObject hello(String nonce) {
        JsonObject hello = new JsonObject();
        hello.addProperty("kind", "hello");
        hello.addProperty("bootstrap", 1);
        hello.addProperty("clientNonce", nonce);
        hello.addProperty("controlGeneration", 0);
        JsonObject features = new JsonObject();
        features.addProperty("machine.configure", 1);
        features.addProperty("machine.snapshot", 1);
        hello.add("features", features);
        return hello;
    }

    JsonObject packet(String kind, String requestId) {
        JsonObject packet = new JsonObject();
        packet.addProperty("kind", kind);
        packet.add("sessionId", welcome.get("sessionId"));
        packet.add("dimension", welcome.get("dimension"));
        if (requestId != null) packet.addProperty("requestId", requestId);
        return packet;
    }

    JsonObject request(String id) {
        JsonObject request = packet("request", id);
        request.addProperty("operationId", "machine.configure");
        request.addProperty("version", 1);
        request.addProperty("controlGeneration", 0);
        request.add("body", new JsonObject());
        return request;
    }

    JsonObject send(JsonObject request) { return dispatcher.receive(request, this); }

    static void field(JsonObject value, String key, String expected) {
        check(value.has(key) && value.get(key).getAsString().equals(expected), key + " expected " + expected + ": " + value);
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
