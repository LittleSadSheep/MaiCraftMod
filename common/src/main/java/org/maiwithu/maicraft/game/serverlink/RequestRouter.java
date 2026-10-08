// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.serverlink;

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
import static org.maiwithu.maicraft.game.serverlink.ClientRequest.*;

/**
 * 客户端一侧请求路由：登记操作登记、维持与服务端的会话、逐刻分发排队请求并跟踪结果。
 * 所有请求都发往服务端 MaiCraft；没有等价的本地执行，服务端未确认时一律不下发。
 */
public final class RequestRouter {
    @FunctionalInterface
    public interface MutationGate {
        /** 发送前先占用本刻角色操作额度；无法取得额度时让请求继续排队。 */
        boolean submit(ClientRequest request, Runnable send);
    }

    private final Map<String, ClientOperation> operations = new LinkedHashMap<>();
    private final Runnable requireThread;
    private final Consumer<Runnable> dispatch;
    private final MutationGate mutations;
    private final ServerSessionConnection session;
    private final ClientRequestLedger ledger;
    private long tick;
    private long dispatchedTick = Long.MIN_VALUE;
    private int dispatchedThisTick;
    private int sentInScope;
    private boolean rotationRequested;
    private Map<String, ServerCapabilityState.Feature> renewalFeatures = Map.of();
    private long renewalConnection = -1, renewalBinding = -1;

    public RequestRouter(BooleanSupplier available, Predicate<JsonObject> sender, Runnable requireThread,
                         Consumer<Runnable> dispatch, MutationGate mutations) {
        this.requireThread = requireThread;
        this.dispatch = dispatch;
        this.mutations = mutations;
        session = new ServerSessionConnection(available, sender);
        ledger = new ClientRequestLedger(session);
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
        revokeReadRefresh();
        ledger.retire(request -> true, "player, connection or world changed");
        session.bind(connection, binding, dimension, authority, allowed, tick, operations.values());
        sentInScope = 0;
        rotationRequested = false;
        clearRenewal();
    }

    public void control(long authority, boolean allowed) {
        requireThread.run();
        if (session.authority != authority || session.allowed != allowed) {
            clearRenewal();
            revokeReadRefresh();
            session.control(authority, allowed);
            ledger.retire(request -> request.operation.mutating(), "control ownership changed");
        }
    }

    public void disconnect() {
        requireThread.run();
        clearRenewal();
        revokeReadRefresh();
        session.disconnect();
        ledger.retire(request -> true, "disconnected; submitted effects remain unknown until reconciled");
    }

    public void close() {
        requireThread.run();
        revokeReadRefresh();
        ledger.retire(request -> true, "client runtime stopped");
        session.close();
        session.disconnect();
        clearRenewal();
    }

    public ClientRequest submit(String operationId, JsonObject arguments, boolean mutating) {
        requireThread.run();
        ClientOperation operation = operations.get(operationId);
        if (operation == null) throw new IllegalArgumentException("unknown client operation: " + operationId);
        if (operation.mutating() != mutating) throw new IllegalArgumentException("mutation flag disagrees with contract");
        if (arguments == null) throw new IllegalArgumentException("operation arguments required");
        if (arguments.toString().length() > 7000) throw new IllegalArgumentException("operation arguments too large");
        ClientRequest request = new ClientRequest(operation, arguments, session.binding,
                session.authority, requireThread, dispatch);
        ledger.add(request);
        return request;
    }

    public Optional<Snapshot> poll(UUID id) {
        requireThread.run();
        return Optional.ofNullable(ledger.requests.get(id)).map(ClientRequest::snapshot);
    }

    /** 只向原服务端查询既有请求，超时后也不重新提交，避免重复执行。 */
    public Optional<Snapshot> query(UUID id) {
        requireThread.run();
        ClientRequest request = ledger.requests.get(id);
        if (request != null) ledger.query(request, tick);
        return poll(id);
    }

    public void cancel(UUID id) {
        requireThread.run();
        ClientRequest request = ledger.requests.get(id);
        if (request != null) ledger.retire(request, "caller cancelled this request");
    }

    public void receive(JsonObject envelope, long receivedConnection) {
        requireThread.run();
        if (envelope == null) return;
        try {
            if (!session.receive(envelope, receivedConnection)
                    && ServerCapabilityState.text(envelope, "kind").equals("receipt"))
                ledger.receive(envelope, receivedConnection);
            observeReadExpiry(envelope, receivedConnection);
            if (session.capabilities.state == ServerCapabilityState.State.DENIED)
                ledger.retire(request -> request.operation.mutating(), "server authorization unavailable");
            if (session.capabilities.state != ServerCapabilityState.State.NEGOTIATING) clearRenewal();
            var scope = session.capabilities.scope;
            if (scope != null && receivedConnection == scope.connection()
                    && scope.sessionId().equals(ServerCapabilityState.text(envelope, "sessionId"))) {
                if (ServerCapabilityState.text(envelope, "code").equals("receipt_capacity")) rotationRequested = true;
                if (envelope.has("remainingRequests")) sentInScope = Math.max(sentInScope,
                        session.capabilities.limit("maxRequests", 512, 512) - Math.max(0, envelope.get("remainingRequests").getAsInt()));
            }
        } catch (RuntimeException malformed) {
            // 无效的远端结果不能解除写操作隔离。
        }
    }

    /** 玩家自行控制角色时也继续观察连接生命周期，并按期限查询既有请求。 */
    public void observe(long tick) {
        requireThread.run();
        this.tick = tick;
        session.tick(tick, operations.values());
        ledger.tick(tick);
        if (session.capabilities.state == ServerCapabilityState.State.READY && !ledger.unresolvedMutation()
                && (rotationRequested || sentInScope >= rotationThreshold() || session.idleRenewalDue(tick))) {
            renewalFeatures = session.capabilities.features();
            renewalConnection = session.connection;
            renewalBinding = session.binding;
            session.bind(session.connection, session.binding, session.dimension, session.authority,
                    session.allowed, tick, operations.values());
            sentInScope = 0;
            rotationRequested = false;
        }
        if (!session.available.getAsBoolean()) revokeReadRefresh();
        if (session.capabilities.state != ServerCapabilityState.State.NEGOTIATING || !session.available.getAsBoolean()) clearRenewal();
    }

    /** 在角色的游戏刻内分发；服务端未完成本次握手时连只读请求也不下发。 */
    public void dispatch(boolean allowMutations) {
        requireThread.run();
        if (!session.serverConfirmed()) return;
        if (dispatchedTick != tick) { dispatchedTick = tick; dispatchedThisTick = 0; }
        int budget = session.capabilities.limit("maxRequestsPerTick", 8, 8) - dispatchedThisTick;
        for (ClientRequest request : List.copyOf(ledger.requests.values())) {
            if (budget <= 0) break;
            if (request.status != Status.QUEUED || request.retired) continue;
            if (request.binding != session.binding) { ledger.retire(request, "stale world binding"); continue; }
            String blocked = dispatchBlock(request.operation, allowMutations);
            if (blocked != null) {
                if (blocked.equals("negotiating") || blocked.equals("awaiting_control_ack")
                        || blocked.equals("unresolved_mutation")) continue;
                request.update(new Result(Status.REJECTED, Effect.NOT_APPLIED, null, blocked,
                        "operation cannot execute under the current conditions"));
                continue;
            }
            if (rotationRequested || sentInScope >= rotationThreshold()) continue;
            if (!sendServer(request)) continue;
            budget--;
            dispatchedThisTick++;
            if (request.operation.mutating()) allowMutations = false;
        }
    }

    /** 返回不能立刻下发的原因；null 表示可以发送。 */
    private String dispatchBlock(ClientOperation operation, boolean allowMutations) {
        var caps = session.capabilities;
        if (caps.policyDenied(operation)) return "server_policy_denied";
        if (session.connection < 0) return "no_world";
        if (caps.state == ServerCapabilityState.State.NEGOTIATING) return "negotiating";
        if (!caps.supports(operation)) return "operation_unsupported";
        // 操作仍可被服务端识别，但未完成本次服务端确认时不能发出。
        if (!session.serverConfirmed()) return "server_confirmation_required";
        if (operation.mutating() && ledger.unresolvedMutation()) return "unresolved_mutation";
        if (operation.mutating() && !session.allowed) return "control_unavailable";
        if (operation.mutating() && !session.mutationPermitted()) return "awaiting_control_ack";
        if (operation.mutating() && !allowMutations) return "mutations_not_allowed";
        return null;
    }

    private boolean sendServer(ClientRequest request) {
        request.scope = session.capabilities.scope;
        request.readRefreshEligible = !request.operation.mutating() && session.allowed;
        Runnable send = () -> {
            JsonObject envelope = request.envelope("request");
            envelope.addProperty("controlGeneration", session.wireGeneration);
            envelope.add("body", request.arguments.deepCopy());
            // 发送前先固定请求身份；发送器可能在包已入队后抛出异常，不能据此重发。
            if (!ledger.submitted(request, tick)) return;
            try {
                if (session.send(envelope)) sentInScope++;
                else {
                    request.submitted = false;
                    request.update(new Result(Status.QUEUED, Effect.NOT_APPLIED, null,
                            "transport_not_submitted", "no packet was submitted"));
                }
            } catch (RuntimeException failure) {
                request.update(new Result(Status.UNKNOWN, Effect.UNKNOWN, null,
                        "transport_send_uncertain", "sender failed; request may already have reached the server"));
            }
        };
        return !request.operation.mutating() ? run(send) : mutations.submit(request, send);
    }

    public boolean supported(String operationId) {
        requireThread.run();
        ClientOperation operation = operations.get(operationId);
        if (operation == null) return false;
        var caps = session.capabilities;
        return session.connection >= 0 && !caps.policyDenied(operation)
                && (caps.state == ServerCapabilityState.State.READY || caps.state == ServerCapabilityState.State.NEGOTIATING)
                && (caps.state != ServerCapabilityState.State.READY || caps.supports(operation));
    }

    /** 连接准入只看本次服务器的有效握手。 */
    public boolean serverConfirmed() { requireThread.run(); return session.serverConfirmed(); }
    public boolean serverConfirmationExpired() { requireThread.run(); return session.serverConfirmationExpired(); }

    /** 同一世界绑定续订保留期时，继续识别此前已协商的操作支持。 */
    public boolean renegotiating(String operationId) {
        requireThread.run();
        if (session.capabilities.state != ServerCapabilityState.State.NEGOTIATING || !session.available.getAsBoolean()
                || session.connection != renewalConnection || session.binding != renewalBinding) return false;
        ClientOperation operation = operations.get(operationId);
        ServerCapabilityState.Feature feature = renewalFeatures.get(operationId);
        return operation != null && feature != null && feature.enabled()
                && feature.version() == operation.version() && feature.mutating() == operation.mutating();
    }

    /** 原只读请求超出保留期后，消费一次新查询许可；新查询不能重用旧请求身份。 */
    public boolean takeExpiredReadForRefresh(UUID id) {
        requireThread.run();
        ClientRequest request = ledger.requests.get(id);
        if (!currentReadRefresh(request) || !request.code.equals("session_expired")
                || request.status != Status.FAILED || ledger.unresolvedMutation()) return false;
        boolean renewing = renegotiating(request.operation.id());
        boolean ready = session.capabilities.supports(request.operation) && (rotationRequested
                || !session.capabilities.scope.sessionId().equals(request.scope.sessionId()));
        if (!renewing && !ready) return false;
        request.readRefreshEligible = false;
        return true;
    }

    private void observeReadExpiry(JsonObject envelope, long receivedConnection) {
        if (!ServerCapabilityState.text(envelope, "code").equals("session_expired")) return;
        ClientRequest request;
        try { request = ledger.requests.get(UUID.fromString(ServerCapabilityState.text(envelope, "requestId"))); }
        catch (IllegalArgumentException invalid) { return; }
        if (request == null || request.operation.mutating() || request.scope == null
                || request.scope.connection() != receivedConnection || !request.code.equals("session_expired")
                || request.status != Status.UNKNOWN) return;
        // 只读请求没有待核对的写入效果，将旧身份作为失败的观察保留在记录中。
        request.update(new Result(Status.FAILED, Effect.NOT_APPLIED, request.result, "session_expired", request.message));
        if (currentReadRefresh(request) && session.capabilities.scope != null
                && session.capabilities.scope.sessionId().equals(request.scope.sessionId())) rotationRequested = true;
    }

    private boolean currentReadRefresh(ClientRequest request) {
        return request != null && request.readRefreshEligible && !request.retired && !request.operation.mutating()
                && request.binding == session.binding && request.controlGeneration == session.authority
                && request.scope != null && request.scope.connection() == session.connection
                && session.allowed && session.available.getAsBoolean();
    }

    private void revokeReadRefresh() { ledger.requests.values().forEach(request -> request.readRefreshEligible = false); }

    public boolean serverSupported(String operationId) {
        requireThread.run();
        ClientOperation operation = operations.get(operationId);
        return operation != null && session.capabilities.supports(operation);
    }

    public JsonObject capabilityReport() {
        requireThread.run();
        JsonObject report = session.capabilities.report();
        report.addProperty("server_required", true);
        report.addProperty("server_confirmed", session.serverConfirmed());
        JsonObject entries = new JsonObject();
        operations.forEach((id, operation) -> {
            JsonObject value = new JsonObject();
            value.addProperty("registered", true);
            value.addProperty("version", operation.version());
            value.addProperty("mutating", operation.mutating());
            value.addProperty("supported", serverSupported(id));
            entries.add(id, value);
        });
        report.add("operations", entries);
        JsonArray unresolved = new JsonArray();
        ledger.requests.values().stream().filter(request -> request.operation.mutating()
                && request.submitted && request.effect == Effect.UNKNOWN).forEach(request -> unresolved.add(request.id().toString()));
        report.add("unresolved_mutations", unresolved);
        report.addProperty("control_allowed", session.allowed);
        report.addProperty("requests_in_scope", sentInScope);
        return report;
    }

    private static boolean run(Runnable action) { action.run(); return true; }
    private void clearRenewal() { renewalFeatures = Map.of(); renewalConnection = renewalBinding = -1; }
    private int rotationThreshold() { return Math.max(1, session.capabilities.limit("maxRequests", 512, 512) - 8); }
}
