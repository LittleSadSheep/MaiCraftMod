// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import static org.maiwithu.maicraft.client.server.ClientRequestReceipt.*;

/** One client-thread router for server enhancement and explicitly equivalent client fallbacks. */
public final class ClientRequestRouter {
    @FunctionalInterface
    public interface MutationGate {
        /** Claim the current actor's mutation slot before invoking send; false leaves the request queued. */
        boolean submit(ClientRequestReceipt receipt, Runnable send);
    }

    private final Map<String, ClientOperation> operations = new LinkedHashMap<>();
    private final Runnable requireThread;
    private final Consumer<Runnable> dispatch;
    private final MutationGate mutations;
    private final ServerSessionConnection session;
    private final ClientReceiptLedger ledger;
    private long tick;
    private long dispatchedTick = Long.MIN_VALUE;
    private int dispatchedThisTick;
    private int sentInScope;
    private boolean rotationRequested;

    public ClientRequestRouter(BooleanSupplier available, Predicate<JsonObject> sender, Runnable requireThread,
                               Consumer<Runnable> dispatch, MutationGate mutations) {
        this(available, sender, requireThread, dispatch, mutations, MutationPersistence.NONE);
    }

    public ClientRequestRouter(BooleanSupplier available, Predicate<JsonObject> sender, Runnable requireThread,
                               Consumer<Runnable> dispatch, MutationGate mutations, MutationPersistence persistence) {
        this.requireThread = requireThread;
        this.dispatch = dispatch;
        this.mutations = mutations;
        session = new ServerSessionConnection(available, sender);
        ledger = new ClientReceiptLedger(session, persistence);
    }

    public void register(ClientOperation operation) {
        requireThread.run();
        ClientOperation old = operations.get(operation.id());
        if (old != null && (old.version() != operation.version() || old.mutating() != operation.mutating()))
            throw new IllegalArgumentException("operation contract cannot change in place");
        if (old == null && session.connection >= 0)
            throw new IllegalStateException("register operation contracts before joining a world");
        operations.put(operation.id(), operation);
    }

    public void bind(long connection, long binding, String dimension, long authority, boolean allowed, long tick) {
        requireThread.run();
        this.tick = tick;
        if (session.connection == connection && session.binding == binding) {
            control(authority, allowed);
            return;
        }
        if (session.connection != connection) session.disconnect();
        ledger.retire(request -> true, "player, connection or world changed");
        session.bind(connection, binding, dimension, authority, allowed, tick, operations.values());
        sentInScope = 0;
        rotationRequested = false;
    }

    public void control(long authority, boolean allowed) {
        requireThread.run();
        if (session.authority != authority || session.allowed != allowed) {
            session.control(authority, allowed);
            ledger.retire(request -> request.operation.mutating(), "control ownership changed");
        }
    }

    public void disconnect() {
        requireThread.run();
        session.disconnect();
        ledger.retire(request -> true, "disconnected; submitted effects remain unknown until reconciled");
    }

    public void close() {
        requireThread.run();
        ledger.retire(request -> true, "client runtime stopped");
        session.close();
        session.disconnect();
    }

    public ClientRequestReceipt submit(String operationId, JsonObject arguments, boolean mutating) {
        requireThread.run();
        ClientOperation operation = operations.get(operationId);
        if (operation == null) throw new IllegalArgumentException("unknown client operation: " + operationId);
        if (operation.mutating() != mutating) throw new IllegalArgumentException("mutation flag disagrees with contract");
        if (arguments == null) throw new IllegalArgumentException("operation arguments required");
        if (arguments.toString().length() > 7000) throw new IllegalArgumentException("operation arguments too large");
        ClientRequestReceipt receipt = new ClientRequestReceipt(operation, arguments, session.binding,
                session.authority, requireThread, dispatch);
        ledger.add(receipt);
        return receipt;
    }

    public Optional<Snapshot> poll(UUID id) {
        requireThread.run();
        return Optional.ofNullable(ledger.requests.get(id)).map(ClientRequestReceipt::snapshot);
    }

    /** Query only the original server identity; never resubmit, including after a timeout. */
    public Optional<Snapshot> query(UUID id) {
        requireThread.run();
        ClientRequestReceipt receipt = ledger.requests.get(id);
        if (receipt != null) ledger.query(receipt, tick);
        return poll(id);
    }

    public void cancel(UUID id) {
        requireThread.run();
        ClientRequestReceipt receipt = ledger.requests.get(id);
        if (receipt != null) ledger.retire(receipt, "caller cancelled this request");
    }

    public void receive(JsonObject envelope, long receivedConnection) {
        requireThread.run();
        if (envelope == null) return;
        try {
            if (!session.receive(envelope, receivedConnection)
                    && ServerCapabilityState.text(envelope, "kind").equals("receipt"))
                ledger.receive(envelope, receivedConnection);
            if (session.capabilities.state == ServerCapabilityState.State.DENIED)
                ledger.retire(request -> request.operation.mutating(), "server authorization unavailable");
            var scope = session.capabilities.scope;
            if (scope != null && receivedConnection == scope.connection()
                    && scope.sessionId().equals(ServerCapabilityState.text(envelope, "sessionId"))) {
                if (ServerCapabilityState.text(envelope, "code").equals("receipt_capacity")) rotationRequested = true;
                if (envelope.has("remainingRequests")) sentInScope = Math.max(sentInScope,
                        session.capabilities.limit("maxRequests", 512, 512) - Math.max(0, envelope.get("remainingRequests").getAsInt()));
            }
        } catch (RuntimeException malformed) {
            // A malformed remote observation cannot release a mutation fence or cause a fallback.
        }
    }

    /** Observe lifecycle and query receipts even when the human owns the body. */
    public void observe(long tick) {
        requireThread.run();
        this.tick = tick;
        session.tick(tick, operations.values());
        ledger.tick(tick);
        if (session.capabilities.state == ServerCapabilityState.State.READY && !ledger.unresolvedMutation()
                && (rotationRequested || sentInScope >= rotationThreshold())) {
            session.bind(session.connection, session.binding, session.dimension, session.authority,
                    session.allowed, tick, operations.values());
            sentInScope = 0;
            rotationRequested = false;
        }
    }

    /** Call within the actor tick; native client backends retain their ordinary actor/menu checks. */
    public void dispatch(boolean allowMutations) {
        dispatch(allowMutations, receipt -> true);
    }

    public void dispatch(boolean allowMutations, Predicate<ClientRequestReceipt> selectedOwner) {
        requireThread.run();
        if (dispatchedTick != tick) { dispatchedTick = tick; dispatchedThisTick = 0; }
        int budget = session.capabilities.limit("maxRequestsPerTick", 8, 8) - dispatchedThisTick;
        for (ClientRequestReceipt receipt : List.copyOf(ledger.requests.values())) {
            if (budget <= 0) break;
            if (receipt.status != Status.QUEUED || receipt.retired) continue;
            if (receipt.binding != session.binding) { ledger.retire(receipt, "stale world binding"); continue; }
            if (receipt.operation.mutating() && (!allowMutations || !selectedOwner.test(receipt))) continue;
            var choice = choose(receipt.operation, receipt.arguments, receipt.fallbackAfterRejection);
            if (!choice.ready()) {
                if (choice.reason().equals("negotiating") || choice.reason().equals("awaiting_control_ack")
                        || choice.reason().equals("unresolved_mutation")) continue;
                receipt.update(new Result(Status.REJECTED, Effect.NOT_APPLIED, null, choice.reason(),
                        "operation cannot execute under the current conditions"));
                continue;
            }
            if (choice.backend() == Backend.SERVER) {
                if (rotationRequested || sentInScope >= rotationThreshold()) continue;
                if (!sendServer(receipt)) continue;
            } else sendClient(receipt);
            budget--;
            dispatchedThisTick++;
            if (receipt.operation.mutating()) allowMutations = false;
        }
    }

    private boolean sendServer(ClientRequestReceipt receipt) {
        receipt.scope = session.capabilities.scope;
        Runnable send = () -> {
            receipt.backend = Backend.SERVER;
            JsonObject envelope = receipt.envelope("request");
            envelope.addProperty("controlGeneration", session.wireGeneration);
            envelope.add("body", receipt.arguments.deepCopy());
            // Pin before crossing the send boundary: a sender may throw after enqueueing the packet.
            if (!ledger.submitted(receipt, tick)) return;
            try {
                if (session.send(envelope)) sentInScope++;
                else {
                    receipt.submitted = false;
                    receipt.backend = Backend.UNSELECTED;
                    receipt.update(new Result(Status.REJECTED, Effect.NOT_APPLIED, null, "transport_not_submitted", "no packet submitted"));
                    ledger.persistence.afterObservation(receipt);
                    receipt.update(new Result(Status.QUEUED, Effect.NOT_APPLIED, null,
                            "transport_not_submitted", "no packet was submitted"));
                }
            } catch (RuntimeException failure) {
                receipt.update(new Result(Status.UNKNOWN, Effect.UNKNOWN, null,
                        "transport_send_uncertain", "sender failed; request may already have reached the server"));
            }
        };
        return !receipt.operation.mutating() ? run(send) : mutations.submit(receipt, send);
    }

    private void sendClient(ClientRequestReceipt receipt) {
        receipt.backend = Backend.CLIENT;
        if (!ledger.submitted(receipt, tick)) return;
        try {
            receipt.operation.fallback().submit(receipt.id(), receipt.arguments.deepCopy(),
                    result -> dispatch.accept(() -> ledger.localCompleted(receipt, result)));
        } catch (RuntimeException failure) {
            receipt.update(new Result(Status.UNKNOWN, Effect.UNKNOWN, null,
                    "client_submission_uncertain", "native submission failed; no retry is permitted"));
        }
    }

    public boolean supported(String operationId) {
        requireThread.run();
        ClientOperation operation = operations.get(operationId);
        return operation != null && choose(operation, new JsonObject(), false).supported();
    }

    public boolean serverSupported(String operationId) {
        requireThread.run();
        ClientOperation operation = operations.get(operationId);
        return operation != null && session.capabilities.supports(operation);
    }

    /** Legacy native callers must also respect known denial and persisted uncertainty. */
    public boolean nativeFallbackAllowed(String operationId) {
        requireThread.run();
        ClientOperation operation = operations.get(operationId);
        return operation != null && !session.capabilities.policyDenied(operation)
                && (!operation.mutating() || !ledger.unresolvedMutation());
    }

    public JsonObject capabilityReport() {
        requireThread.run();
        JsonObject report = session.capabilities.report();
        JsonObject entries = new JsonObject();
        operations.forEach((id, operation) -> entries.add(id, choose(operation, new JsonObject(), false).report(operation)));
        report.add("operations", entries);
        JsonArray unresolved = new JsonArray();
        ledger.requests.values().stream().filter(request -> request.operation.mutating()
                && request.submitted && request.effect == Effect.UNKNOWN).forEach(request -> unresolved.add(request.id().toString()));
        report.add("unresolved_mutations", unresolved);
        report.add("mutation_journal", ledger.persistence.report());
        report.addProperty("control_allowed", session.allowed);
        report.addProperty("requests_in_scope", sentInScope);
        return report;
    }

    private ClientBackendSelection.Choice choose(ClientOperation operation, JsonObject arguments, boolean forceClient) {
        return ClientBackendSelection.choose(operation, arguments, session, ledger.unresolvedMutation(), forceClient);
    }
    private static boolean run(Runnable action) { action.run(); return true; }
    private int rotationThreshold() { return Math.max(1, session.capabilities.limit("maxRequests", 512, 512) - 8); }
}
