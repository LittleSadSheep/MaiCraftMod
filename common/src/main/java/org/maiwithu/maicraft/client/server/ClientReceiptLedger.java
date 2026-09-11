// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import static org.maiwithu.maicraft.client.server.ClientRequestReceipt.*;

/** Bounded receipt history; uncertain mutations survive world changes and prevent implicit replay. */
final class ClientReceiptLedger {
    private static final int MAX_RECEIPTS = 512;
    final Map<UUID, ClientRequestReceipt> requests = new LinkedHashMap<>();
    final ServerSessionConnection session;
    final MutationPersistence persistence;

    ClientReceiptLedger(ServerSessionConnection session, MutationPersistence persistence) {
        this.session = session;
        this.persistence = persistence;
    }

    void add(ClientRequestReceipt receipt) {
        if (requests.size() >= MAX_RECEIPTS) {
            var oldest = requests.entrySet().stream().filter(entry -> entry.getValue().authoritative())
                    .map(Map.Entry::getKey).findFirst();
            oldest.ifPresent(requests::remove);
        }
        if (requests.size() >= MAX_RECEIPTS) throw new IllegalStateException("unsettled receipt capacity reached");
        requests.put(receipt.id(), receipt);
    }

    boolean unresolvedMutation() {
        return persistence.unresolved() || requests.values().stream().anyMatch(request -> request.operation.mutating()
                && request.submitted && request.effect == Effect.UNKNOWN);
    }

    void retire(Predicate<ClientRequestReceipt> predicate, String reason) {
        for (ClientRequestReceipt request : List.copyOf(requests.values())) {
            if (predicate.test(request)) retire(request, reason);
        }
    }

    void retire(ClientRequestReceipt request, String reason) {
        if (request.retired || request.authoritative()) return;
        request.retired = true;
        if (!request.submitted) {
            request.update(new Result(Status.CANCELLED, Effect.NOT_APPLIED, null, "cancelled", reason));
            return;
        }
        if (request.backend == Backend.SERVER && session.canQuery(request.scope)) {
            try { session.send(request.envelope("cancel")); } catch (RuntimeException ignored) { /* unknown */ }
        } else if (request.backend == Backend.CLIENT) {
            try { request.operation.fallback().cancel(request.id()); }
            catch (RuntimeException ignored) { /* cancellation cannot assert rollback */ }
        }
        if (!request.authoritative()) request.update(new Result(Status.UNKNOWN, Effect.UNKNOWN,
                request.result, "cancelled_awaiting_receipt", reason));
        else request.publish();
    }

    boolean submitted(ClientRequestReceipt request, long tick) {
        try { persistence.beforeSubmission(request); }
        catch (RuntimeException unavailable) {
            request.update(new Result(Status.REJECTED, Effect.NOT_APPLIED, null,
                    "mutation_journal_unavailable", "no operation submitted because its identity could not be persisted"));
            return false;
        }
        request.submitted = true;
        request.submittedTick = tick;
        request.nextQueryTick = tick + 20;
        request.stopQueryTick = tick + session.capabilities.limit("retentionTicks", 6000, 72000);
        request.update(new Result(Status.PENDING, Effect.UNKNOWN, null, "awaiting_receipt", "submitted once"));
        return true;
    }

    void receive(JsonObject envelope, long receivedConnection) {
        ClientRequestReceipt request;
        try { request = requests.get(UUID.fromString(ServerCapabilityState.text(envelope, "requestId"))); }
        catch (IllegalArgumentException malformed) { return; }
        if (request == null || request.backend != Backend.SERVER || request.scope == null
                || request.scope.connection() != receivedConnection || request.authoritative()) return;
        if (!request.scope.sessionId().equals(ServerCapabilityState.text(envelope, "sessionId"))
                || !request.scope.dimension().equals(ServerCapabilityState.text(envelope, "dimension"))) return;
        String operation = ServerCapabilityState.text(envelope, "operationId");
        if (!operation.isEmpty() && !operation.equals(request.operation.id())) return;
        if (envelope.has("version") && envelope.get("version").getAsInt() != request.operation.version()) return;
        Result outcome = decode(envelope);
        if (outcome == null) return;
        if (outcome.effect() != Effect.UNKNOWN && (operation.isEmpty() || !envelope.has("version"))) return;
        if (envelope.has("serverTick")) request.serverTick = envelope.get("serverTick").getAsLong();
        boolean equivalentFallback = outcome.status() == Status.REJECTED && outcome.effect() == Effect.NOT_APPLIED
                && (outcome.code().equals("unsupported_operation") || outcome.code().equals("unsupported_version"))
                && !request.retired && request.binding == session.binding
                && request.controlGeneration == session.authority
                && ClientBackendSelection.clientSupported(request.operation)
                && !session.capabilities.policyDenied(request.operation);
        if (equivalentFallback) {
            request.update(outcome);
            persistence.afterObservation(request);
            request.submitted = false;
            request.fallbackAfterRejection = true;
            request.backend = Backend.UNSELECTED;
            request.update(new Result(Status.QUEUED, Effect.NOT_APPLIED, null,
                    "equivalent_client_fallback", outcome.message()));
        } else if (!(request.status == Status.UNKNOWN && outcome.status() == Status.PENDING)) {
            request.update(outcome);
            persistence.afterObservation(request);
        }
    }

    void localCompleted(ClientRequestReceipt request, Result outcome) {
        if (request.backend != Backend.CLIENT || request.authoritative()) return;
        if (outcome == null || outcome.status() == Status.QUEUED) {
            request.update(new Result(Status.UNKNOWN, Effect.UNKNOWN, null,
                    "invalid_client_receipt", "client completion did not describe an outcome"));
        } else request.update(outcome);
        persistence.afterObservation(request);
    }

    void tick(long tick) {
        int queryBudget = 8;
        for (ClientRequestReceipt request : List.copyOf(requests.values())) {
            if (!request.submitted || request.authoritative()) continue;
            if (request.status == Status.PENDING && tick - request.submittedTick >= 100)
                request.update(new Result(Status.UNKNOWN, Effect.UNKNOWN, request.result,
                        "receipt_timeout", "result unknown; the request will not be replayed"));
            if (request.backend == Backend.SERVER && tick >= request.nextQueryTick
                    && tick < request.stopQueryTick && queryBudget > 0 && session.canQuery(request.scope)) {
                query(request, tick);
                queryBudget--;
            }
        }
    }

    void query(ClientRequestReceipt request, long tick) {
        if (request.backend != Backend.SERVER || !request.submitted || request.authoritative()
                || tick < request.nextQueryTick || !session.canQuery(request.scope)) return;
        request.nextQueryTick = tick + (tick - request.submittedTick < 100 ? 20 : 100);
        try { session.send(request.envelope("query")); }
        catch (RuntimeException ignored) { /* read-only reconciliation may be retried */ }
    }

    static Result decode(JsonObject envelope) {
        Status status;
        Effect effect;
        try {
            status = Status.valueOf(ServerCapabilityState.text(envelope, "status").toUpperCase(java.util.Locale.ROOT));
            effect = Effect.valueOf(ServerCapabilityState.text(envelope, "effect").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) { return null; }
        if (status == Status.QUEUED || status == Status.CANCELLED) return null;
        if (status == Status.PENDING || status == Status.UNKNOWN) effect = Effect.UNKNOWN;
        JsonObject result = ServerCapabilityState.object(envelope, "result");
        // A successful protocol dispatch can still carry a business operation of unknown effect.
        if (ServerCapabilityState.text(result, "effect").equals("unknown")
                || ServerCapabilityState.text(result, "status").equals("uncertain")
                || ServerCapabilityState.text(result, "status").equals("unknown")) effect = Effect.UNKNOWN;
        return new Result(status, effect, result, ServerCapabilityState.text(envelope, "code"),
                ServerCapabilityState.text(envelope, "message"));
    }
}
