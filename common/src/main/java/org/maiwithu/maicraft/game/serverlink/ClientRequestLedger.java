// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.serverlink;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.Locale;
import static org.maiwithu.maicraft.game.serverlink.ClientRequest.*;

/** 有界保存客户端发出的请求记录；换世界时保留结果不明的写操作，防止自动重放造成重复效果。 */
final class ClientRequestLedger {
    private static final int MAX_REQUESTS = 512;
    final Map<UUID, ClientRequest> requests = new LinkedHashMap<>();
    final ServerSessionConnection session;

    ClientRequestLedger(ServerSessionConnection session) {
        this.session = session;
    }

    void add(ClientRequest request) {
        if (requests.size() >= MAX_REQUESTS) {
            var oldest = requests.entrySet().stream().filter(entry -> entry.getValue().authoritative())
                    .map(Map.Entry::getKey).findFirst();
            oldest.ifPresent(requests::remove);
        }
        if (requests.size() >= MAX_REQUESTS) throw new IllegalStateException("unsettled request capacity reached");
        requests.put(request.id(), request);
    }

    boolean unresolvedMutation() {
        return requests.values().stream().anyMatch(request -> request.operation.mutating()
                && request.submitted && request.effect == Effect.UNKNOWN);
    }

    void retire(Predicate<ClientRequest> predicate, String reason) {
        for (ClientRequest request : List.copyOf(requests.values())) {
            if (predicate.test(request)) retire(request, reason);
        }
    }

    void retire(ClientRequest request, String reason) {
        if (request.retired || request.authoritative()) return;
        request.retired = true;
        if (!request.submitted) {
            request.update(new Result(Status.CANCELLED, Effect.NOT_APPLIED, null, "cancelled", reason));
            return;
        }
        // 已提交的请求请服务端取消；取消不成功时结果保持不明，等待后续按身份核对。
        if (session.canQuery(request.scope)) {
            try { session.send(request.envelope("cancel")); } catch (RuntimeException ignored) { /* 尚无法确认实际效果 */ }
        }
        if (!request.authoritative()) request.update(new Result(Status.UNKNOWN, Effect.UNKNOWN,
                request.result, "cancelled_awaiting_receipt", reason));
        else request.publish();
    }

    boolean submitted(ClientRequest request, long tick) {
        request.submitted = true;
        request.submittedTick = tick;
        request.nextQueryTick = tick + 20;
        request.stopQueryTick = tick + session.capabilities.limit("retentionTicks", 6000, 72000);
        request.update(new Result(Status.PENDING, Effect.UNKNOWN, null, "awaiting_receipt", "submitted once"));
        return true;
    }

    void receive(JsonObject envelope, long receivedConnection) {
        ClientRequest request;
        try { request = requests.get(UUID.fromString(ServerCapabilityState.text(envelope, "requestId"))); }
        catch (IllegalArgumentException malformed) { return; }
        if (request == null || request.scope == null
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
        // 协议里不会出现的排队与取消状态说明信封不完整，不当作可靠结果。
        if (!(request.status == Status.UNKNOWN && outcome.status() == Status.PENDING)) request.update(outcome);
    }

    void tick(long tick) {
        int queryBudget = 8;
        for (ClientRequest request : List.copyOf(requests.values())) {
            if (!request.submitted || request.authoritative()) continue;
            if (request.status == Status.PENDING && tick - request.submittedTick >= 100)
                request.update(new Result(Status.UNKNOWN, Effect.UNKNOWN, request.result,
                        "receipt_timeout", "result unknown; the request will not be replayed"));
            if (tick >= request.nextQueryTick && tick < request.stopQueryTick
                    && queryBudget > 0 && session.canQuery(request.scope)) {
                query(request, tick);
                queryBudget--;
            }
        }
    }

    void query(ClientRequest request, long tick) {
        if (!request.submitted || request.authoritative()
                || tick < request.nextQueryTick || !session.canQuery(request.scope)) return;
        request.nextQueryTick = tick + (tick - request.submittedTick < 100 ? 20 : 100);
        try { session.send(request.envelope("query")); }
        catch (RuntimeException ignored) { /* 只读核对失败后可以再次查询原请求的结果 */ }
    }

    static Result decode(JsonObject envelope) {
        Status status;
        Effect effect;
        try {
            status = Status.valueOf(ServerCapabilityState.text(envelope, "status").toUpperCase(Locale.ROOT));
            effect = Effect.valueOf(ServerCapabilityState.text(envelope, "effect").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) { return null; }
        if (status == Status.QUEUED || status == Status.CANCELLED) return null;
        if (status == Status.PENDING || status == Status.UNKNOWN) effect = Effect.UNKNOWN;
        JsonObject result = ServerCapabilityState.object(envelope, "result");
        // 协议请求发送成功，并不代表对应操作的实际效果已经明确。
        if (ServerCapabilityState.text(result, "effect").equals("unknown")
                || ServerCapabilityState.text(result, "status").equals("uncertain")
                || ServerCapabilityState.text(result, "status").equals("unknown")) effect = Effect.UNKNOWN;
        return new Result(status, effect, result, ServerCapabilityState.text(envelope, "code"),
                ServerCapabilityState.text(envelope, "message"));
    }
}
