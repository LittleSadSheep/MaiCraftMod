// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One negotiated world scope within one authenticated connection. Accessed on its game thread. */
final class ProtocolSession {
    static final long RETENTION_TICKS = 6000;
    final String id = UUID.randomUUID().toString();
    final String nonce;
    final String dimension;
    final Map<String, ServerFeature> features = new LinkedHashMap<>();
    final RequestLedger ledger = new RequestLedger();
    final long createdTick;
    long lastTick;
    long controlGeneration;
    boolean allowed = true;
    boolean closed;

    ProtocolSession(JsonObject hello, ServerProtocolDispatcher.Peer peer) {
        nonce = ProtocolJson.string(hello, "clientNonce");
        dimension = peer.dimension();
        controlGeneration = ProtocolJson.number(hello, "controlGeneration");
        if (controlGeneration < 0) throw new IllegalArgumentException("Negative control generation");
        createdTick = lastTick = peer.tick();
        if (!hello.has("features") || !hello.get("features").isJsonObject())
            throw new IllegalArgumentException("Missing feature versions");
        JsonObject offered = hello.getAsJsonObject("features");
        for (ServerFeature feature : peer.features()) {
            // A handler implements one concrete version. New versions keep the bootstrap stable.
            if (offered.has(feature.operationId()) && ProtocolJson.number(offered, feature.operationId()) >= feature.version())
                features.put(feature.operationId(), feature);
        }
    }

    boolean expired(long tick) { return tick - lastTick > RETENTION_TICKS; }

    JsonObject welcome(long tick) {
        JsonObject response = new JsonObject();
        response.addProperty("bootstrap", ProtocolJson.BOOTSTRAP);
        response.addProperty("kind", "welcome");
        response.addProperty("status", "succeeded");
        response.addProperty("clientNonce", nonce);
        response.addProperty("sessionId", id);
        response.addProperty("dimension", dimension);
        response.addProperty("controlGeneration", controlGeneration);
        response.addProperty("allowed", allowed && !closed);
        response.addProperty("serverTick", tick);
        response.addProperty("remainingRequests", ledger.remainingRequests());
        JsonObject supported = new JsonObject();
        features.forEach((operation, feature) -> supported.add(operation, feature.json()));
        response.add("features", supported);
        JsonObject limits = new JsonObject();
        limits.addProperty("maxEnvelopeChars", ProtocolJson.MAX_ENVELOPE_CHARS);
        limits.addProperty("maxRequestChars", ProtocolJson.MAX_REQUEST_CHARS);
        limits.addProperty("maxRequests", RequestLedger.MAX_REQUESTS);
        limits.addProperty("recentReadResults", RequestLedger.RECENT_READ_RESULTS);
        limits.addProperty("readOnlyScopeMayExpire", true);
        limits.addProperty("retentionTicks", RETENTION_TICKS);
        limits.addProperty("maxRequestsPerTick", ServerProtocolDispatcher.MAX_REQUESTS_PER_TICK);
        limits.addProperty("maxSessions", ServerProtocolDispatcher.MAX_SESSIONS);
        response.add("limits", limits);
        return response;
    }
}
