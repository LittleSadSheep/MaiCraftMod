// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import com.google.gson.JsonObject;

/** Reserves request identity before invoking native code and preserves uncertain outcomes. */
final class ProtocolRequests {
    private ProtocolRequests() {}

    static JsonObject execute(ProtocolSession session, JsonObject request, ServerProtocolDispatcher.Peer peer, boolean limited) {
        String requestId = ProtocolJson.string(request, "requestId");
        String fingerprint = RequestLedger.fingerprint(request);
        JsonObject previous = session.ledger.lookup(requestId);
        if (previous != null) {
            if (session.ledger.matches(requestId, fingerprint)) return previous;
            return ProtocolReplies.error(request, "rejected", "unknown", "request_conflict",
                    "Request identity already belongs to different content; query the original receipt", peer.tick());
        }
        var operationField = request.get("operationId");
        ServerFeature requestedFeature = operationField != null && operationField.isJsonPrimitive()
                ? session.features.get(operationField.getAsString()) : null;
        boolean readOnly = requestedFeature != null && !requestedFeature.mutating();
        if (session.ledger.full(readOnly))
            return ProtocolReplies.reject(request, "receipt_capacity", "Open a new session before new work", peer.tick());
        JsonObject pending = ProtocolReplies.response("receipt", request, "pending", "unknown", peer.tick());
        session.ledger.store(requestId, fingerprint, pending, readOnly);
        JsonObject result;
        boolean invoked = false;
        boolean mutating = true;
        try {
            if (limited) throw ServerOperationException.notApplied("rate_limited", "Per-tick request budget exhausted");
            String operationId = ProtocolJson.string(request, "operationId");
            long version = ProtocolJson.number(request, "version");
            long generation = ProtocolJson.number(request, "controlGeneration");
            if (session.closed) throw ServerOperationException.notApplied("session_closed", "Session is closed");
            if (!session.dimension.equals(peer.dimension()))
                throw ServerOperationException.notApplied("dimension_changed", "Player has left the negotiated world");
            ServerFeature feature = session.features.get(operationId);
            if (feature == null)
                throw ServerOperationException.notApplied("unsupported_operation", "Operation was not negotiated");
            if (version != feature.version())
                throw ServerOperationException.notApplied("unsupported_version", "Operation version was not negotiated");
            mutating = feature.mutating();
            if (!feature.enabled())
                throw ServerOperationException.notApplied("authorization_denied", "Operation disabled by server policy");
            if (mutating && (!session.allowed || generation != session.controlGeneration || !peer.mayMutate()))
                throw ServerOperationException.notApplied("control_revoked", "Player control no longer permits this mutation");
            if (!request.has("body") || !request.get("body").isJsonObject())
                throw ServerOperationException.notApplied("invalid_request", "Operation body must be an object");
            invoked = true;
            JsonObject body = peer.execute(operationId, request.getAsJsonObject("body").deepCopy());
            result = ProtocolReplies.response("receipt", request, "succeeded", mutating ? "applied" : "not_applied", peer.tick());
            result.addProperty("remainingRequests", session.ledger.remainingRequests());
            result.add("result", body);
            // Business outcomes remain in result; a protocol success only means the handler returned.
            ProtocolJson.encode(result);
        } catch (ServerOperationException rejected) {
            result = ProtocolReplies.reject(request, rejected.code(), rejected.getMessage(), peer.tick());
        } catch (RuntimeException failed) {
            String effect = invoked && mutating ? "unknown" : "not_applied";
            result = ProtocolReplies.error(request, invoked ? "failed" : "rejected", effect,
                    invoked ? "handler_failed" : "invalid_request",
                    invoked ? "Handler did not produce a bounded result; reconcile before continuing" : "Malformed operation request", peer.tick());
        }
        result.addProperty("remainingRequests", session.ledger.remainingRequests());
        session.ledger.store(requestId, fingerprint, result, readOnly);
        return result;
    }

    static JsonObject query(ProtocolSession session, JsonObject request, long tick) {
        JsonObject receipt = session.ledger.lookup(ProtocolJson.string(request, "requestId"));
        return receipt != null ? receipt : ProtocolReplies.error(request, "unknown", "unknown", "request_unknown",
                "No receipt exists in this session; this does not authorize replay", tick);
    }

    static JsonObject cancel(ProtocolSession session, JsonObject request, long tick) {
        String requestId = ProtocolJson.string(request, "requestId");
        JsonObject receipt = session.ledger.lookup(requestId);
        if (receipt != null) return receipt;
        if (session.ledger.full()) {
            // Closing the entire scope prevents a late request even when no tombstone fits.
            session.closed = true;
            session.allowed = false;
            return ProtocolReplies.reject(request, "cancelled", "Session closed to preserve cancellation", tick);
        }
        receipt = ProtocolReplies.reject(request, "cancelled", "Request cancelled before execution", tick);
        receipt.addProperty("remainingRequests", session.ledger.remainingRequests() - 1);
        session.ledger.store(requestId, null, receipt);
        return receipt;
    }
}
