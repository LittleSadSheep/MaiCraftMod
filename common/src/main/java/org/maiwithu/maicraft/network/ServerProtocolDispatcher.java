// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic connection-scoped protocol engine; no Minecraft classes are needed to test it. */
public final class ServerProtocolDispatcher {
    public static final int MAX_REQUESTS_PER_TICK = 8;
    public static final int MAX_SESSIONS = 8;

    public interface Peer {
        String dimension();
        long tick();
        boolean mayMutate();
        List<ServerFeature> features();
        JsonObject execute(String operationId, JsonObject body);
    }

    private final Map<String, ProtocolSession> sessions = new LinkedHashMap<>();
    private long rateTick = Long.MIN_VALUE;
    private int requestsThisTick;

    public JsonObject receive(JsonObject request, Peer peer) {
        try {
            ProtocolJson.encodeRequest(request);
            String kind = ProtocolJson.string(request, "kind");
            if (request.has("bootstrap") && ProtocolJson.number(request, "bootstrap") != ProtocolJson.BOOTSTRAP)
                return failure(request, kind, "unsupported_bootstrap", "Bootstrap version is unsupported", peer.tick());
            if (kind.equals("hello")) return hello(request, peer);
            String sessionId = ProtocolJson.string(request, "sessionId");
            ProtocolSession session = sessions.get(sessionId);
            if (session == null || session.expired(peer.tick())) {
                sessions.remove(sessionId);
                return ProtocolReplies.error(request, "unknown", "unknown", "session_expired",
                        "Session is absent or expired; previous effects cannot be replayed", peer.tick());
            }
            if (!session.dimension.equals(ProtocolJson.string(request, "dimension")))
                return ProtocolReplies.reject(request, "session_scope_mismatch", "Envelope does not match the session world", peer.tick());
            session.lastTick = peer.tick();
            return switch (kind) {
                case "query" -> ProtocolRequests.query(session, request, peer.tick());
                case "cancel" -> ProtocolRequests.cancel(session, request, peer.tick());
                case "close" -> close(session, request, peer.tick());
                case "control" -> control(session, request, peer);
                case "request" -> ProtocolRequests.execute(session, request, peer, limited(peer.tick()));
                default -> ProtocolReplies.reject(request, "invalid_kind", "Unknown envelope kind", peer.tick());
            };
        } catch (RuntimeException malformed) {
            return ProtocolReplies.reject(request, "invalid_envelope", "Malformed or oversized envelope", peer.tick());
        }
    }

    private JsonObject hello(JsonObject request, Peer peer) {
        if (ProtocolJson.number(request, "bootstrap") != ProtocolJson.BOOTSTRAP)
            return failure(request, "hello", "unsupported_bootstrap", "Bootstrap version is unsupported", peer.tick());
        String nonce = ProtocolJson.string(request, "clientNonce");
        if (request.has("dimension") && !peer.dimension().equals(ProtocolJson.string(request, "dimension")))
            return failure(request, "hello", "dimension_changed", "Hello belongs to a different world", peer.tick());
        sessions.values().removeIf(session -> session.expired(peer.tick()));
        for (ProtocolSession session : sessions.values()) {
            if (session.nonce.equals(nonce)) {
                if (session.closed || !session.dimension.equals(peer.dimension()))
                    return failure(request, "hello", "session_closed", "Use a new nonce for the current world", peer.tick());
                session.lastTick = peer.tick();
                return session.welcome(peer.tick());
            }
        }
        if (limited(peer.tick())) return failure(request, "hello", "rate_limited", "Per-tick request budget exhausted", peer.tick());
        if (sessions.size() >= MAX_SESSIONS) {
            // Retiring a whole read-only scope invalidates its IDs; it cannot enable a replay.
            var oldRead = sessions.values().stream().filter(session -> session.closed && session.ledger.readOnlyHistory())
                    .map(session -> session.id).findFirst();
            oldRead.ifPresent(sessions::remove);
        }
        if (sessions.size() >= MAX_SESSIONS)
            return failure(request, "hello", "session_capacity", "Receipt retention prevents another session yet", peer.tick());
        ProtocolSession session = new ProtocolSession(request, peer);
        JsonObject welcome = session.welcome(peer.tick());
        ProtocolJson.encode(welcome);
        // A new scope revokes existing mutation authority while leaving its receipts queryable.
        invalidateWorld();
        sessions.put(session.id, session);
        return welcome;
    }

    private JsonObject control(ProtocolSession session, JsonObject request, Peer peer) {
        long generation = ProtocolJson.number(request, "controlGeneration");
        boolean allowed = ProtocolJson.bool(request, "allowed");
        if (session.closed || !session.dimension.equals(peer.dimension()))
            return failure(request, "control", "session_closed", "Session is no longer active", peer.tick());
        if (generation < session.controlGeneration || generation == session.controlGeneration && allowed != session.allowed)
            return failure(request, "control", "stale_control", "Control changes must advance the generation", peer.tick());
        session.controlGeneration = generation;
        session.allowed = allowed;
        JsonObject response = ProtocolReplies.response("control", request, "succeeded", "not_applied", peer.tick());
        response.addProperty("allowed", allowed);
        return response;
    }

    private JsonObject close(ProtocolSession session, JsonObject request, long tick) {
        session.closed = true;
        session.allowed = false;
        JsonObject response = ProtocolReplies.response("close", request, "succeeded", "not_applied", tick);
        response.addProperty("allowed", false);
        return response;
    }

    public void invalidateWorld() {
        sessions.values().forEach(session -> { session.closed = true; session.allowed = false; });
    }

    private boolean limited(long tick) {
        if (tick != rateTick) { rateTick = tick; requestsThisTick = 0; }
        return ++requestsThisTick > MAX_REQUESTS_PER_TICK;
    }

    private JsonObject failure(JsonObject request, String kind, String code, String message, long tick) {
        JsonObject reply = ProtocolReplies.reject(request, code, message, tick);
        reply.addProperty("kind", kind.equals("hello") ? "welcome" : kind);
        return reply;
    }
}
