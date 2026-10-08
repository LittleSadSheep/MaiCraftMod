// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.serverlink;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.Objects;

/** 单次向服务端提交的请求记录：保留唯一身份，取消或超时后仍凭它核对实际效果。 */
public final class ClientRequest {
    public enum Status { QUEUED, PENDING, SUCCEEDED, REJECTED, FAILED, CANCELLED, UNKNOWN }
    public enum Effect { NOT_APPLIED, APPLIED, UNKNOWN }

    /** SUCCEEDED 表示分发成功，业务是否完成仍由具体操作的结果字段决定。 */
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
                           Status status, Effect effect, boolean retired,
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
    Status status = Status.QUEUED;
    Effect effect = Effect.NOT_APPLIED;
    boolean retired;
    boolean submitted;
    boolean readRefreshEligible;
    String code = "queued";
    String message = "waiting for a client tick";
    JsonObject result = new JsonObject();
    long serverTick = -1;
    long submittedTick;
    long nextQueryTick;
    long stopQueryTick;
    ServerCapabilityState.Scope scope;

    ClientRequest(ClientOperation operation, JsonObject arguments, long binding,
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
        return new Snapshot(id, operation.id(), operation.version(), operation.mutating(),
                status, effect, retired, code, message, serverTick, result);
    }

    /** 记录的观察者始终在客户端线程运行；结算完成后新登记的观察者也遵守此规则。 */
    public void onUpdate(Consumer<Snapshot> observer) {
        Objects.requireNonNull(observer, "observer");
        dispatch.accept(() -> {
            requireThread.run();
            if (observers.size() >= 32) throw new IllegalStateException("request observer limit reached");
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
