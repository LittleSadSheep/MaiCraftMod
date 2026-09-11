// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** One submission identity, retained for reconciliation even after cancellation or a timeout. */
public final class ClientRequestReceipt {
    public enum Status { QUEUED, PENDING, SUCCEEDED, REJECTED, FAILED, CANCELLED, UNKNOWN }
    public enum Effect { NOT_APPLIED, APPLIED, UNKNOWN }
    public enum Backend { UNSELECTED, SERVER, CLIENT }

    /** SUCCEEDED describes dispatch; operation-specific result fields still decide task success. */
    public record Result(Status status, Effect effect, JsonObject result, String code, String message) {
        public Result {
            if (status == null || effect == null) throw new IllegalArgumentException("status/effect required");
            result = result == null ? new JsonObject() : result.deepCopy();
            code = code == null ? "" : code;
            message = message == null ? "" : message;
        }
        @Override public JsonObject result() { return result.deepCopy(); }
    }

    public record Snapshot(UUID requestId, String operationId, int version, boolean mutating,
                           Backend backend, Status status, Effect effect, boolean retired,
                           String code, String message, long serverTick, JsonObject result) {
        public Snapshot { result = result.deepCopy(); }
        @Override public JsonObject result() { return result.deepCopy(); }
        public boolean unresolvedMutation() { return mutating && effect == Effect.UNKNOWN; }
        public boolean settled() { return status != Status.QUEUED && status != Status.PENDING; }
    }

    private final UUID id = UUID.randomUUID();
    final ClientOperation operation;
    final JsonObject arguments;
    final long binding;
    final long controlGeneration;
    final Runnable requireThread;
    final Consumer<Runnable> dispatch;
    final List<Consumer<Snapshot>> observers = new ArrayList<>();
    Backend backend = Backend.UNSELECTED;
    Status status = Status.QUEUED;
    Effect effect = Effect.NOT_APPLIED;
    boolean retired;
    boolean submitted;
    boolean fallbackAfterRejection;
    String code = "queued";
    String message = "waiting for a client tick";
    JsonObject result = new JsonObject();
    long serverTick = -1;
    long submittedTick;
    long nextQueryTick;
    long stopQueryTick;
    ServerCapabilityState.Scope scope;

    ClientRequestReceipt(ClientOperation operation, JsonObject arguments, long binding,
                         long generation, Runnable requireThread, Consumer<Runnable> dispatch) {
        this.operation = operation;
        this.arguments = arguments.deepCopy();
        this.binding = binding;
        this.controlGeneration = generation;
        this.requireThread = requireThread;
        this.dispatch = dispatch;
    }

    public UUID id() { return id; }
    public Snapshot snapshot() {
        requireThread.run();
        return new Snapshot(id, operation.id(), operation.version(), operation.mutating(), backend,
                status, effect, retired, code, message, serverTick, result);
    }

    /** Every observer invocation uses the client executor, including registration after settlement. */
    public void onUpdate(Consumer<Snapshot> observer) {
        java.util.Objects.requireNonNull(observer, "observer");
        dispatch.accept(() -> {
            requireThread.run();
            if (observers.size() >= 32) throw new IllegalStateException("receipt observer limit reached");
            observers.add(observer);
            observer.accept(snapshot());
        });
    }

    void update(Result outcome) {
        status = outcome.status();
        effect = outcome.effect();
        result = outcome.result();
        code = outcome.code();
        message = outcome.message();
        publish();
    }

    void publish() {
        for (Consumer<Snapshot> observer : List.copyOf(observers)) {
            dispatch.accept(() -> observer.accept(snapshot()));
        }
    }

    boolean authoritative() {
        return (status == Status.SUCCEEDED || status == Status.REJECTED || status == Status.FAILED
                || status == Status.CANCELLED) && effect != Effect.UNKNOWN;
    }

    JsonObject envelope(String kind) {
        JsonObject value = new JsonObject();
        value.addProperty("kind", kind);
        value.addProperty("bootstrap", 1);
        value.addProperty("sessionId", scope.sessionId());
        value.addProperty("dimension", scope.dimension());
        value.addProperty("requestId", id.toString());
        value.addProperty("operationId", operation.id());
        value.addProperty("version", operation.version());
        return value;
    }
}
