// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Client-thread negotiation and control lease; this class never replays an operation. */
final class ServerSessionConnection {
    final ServerCapabilityState capabilities = new ServerCapabilityState();
    final BooleanSupplier available;
    final Predicate<JsonObject> sender;
    long connection = -1;
    long binding = -1;
    String dimension = "";
    String nonce = "";
    long authority;
    long wireGeneration;
    boolean allowed;
    boolean acknowledged;
    long helloDeadline;
    long controlRetryTick;
    int controlAttempts;
    long observedTick, lastScopeActivityTick;

    ServerSessionConnection(BooleanSupplier available, Predicate<JsonObject> sender) {
        this.available = available;
        this.sender = sender;
    }

    void bind(long connection, long binding, String dimension, long authority, boolean allowed,
              long tick, Collection<ClientOperation> operations) {
        observedTick = lastScopeActivityTick = tick;
        if (this.connection == connection) close();
        this.connection = connection;
        this.binding = binding;
        this.dimension = dimension;
        this.authority = authority;
        this.allowed = allowed;
        wireGeneration = 0;
        acknowledged = false;
        nonce = UUID.randomUUID().toString();
        capabilities.reset(ServerCapabilityState.State.CLIENT_ONLY, "channel_unavailable");
        if (available.getAsBoolean()) hello(tick, operations);
    }

    private void hello(long tick, Collection<ClientOperation> operations) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("kind", "hello");
        envelope.addProperty("bootstrap", 1);
        envelope.addProperty("clientNonce", nonce);
        envelope.addProperty("controlGeneration", wireGeneration);
        JsonObject versions = new JsonObject();
        operations.forEach(operation -> versions.addProperty(operation.id(), operation.version()));
        envelope.add("features", versions);
        capabilities.reset(ServerCapabilityState.State.NEGOTIATING, "awaiting_welcome");
        helloDeadline = tick + 100;
        if (!send(envelope)) capabilities.reset(ServerCapabilityState.State.CLIENT_ONLY, "hello_not_sent");
    }

    void tick(long tick, Collection<ClientOperation> operations) {
        observedTick = tick;
        if (connection < 0) return;
        if (!available.getAsBoolean()) {
            if (capabilities.state == ServerCapabilityState.State.READY) {
                capabilities.state = ServerCapabilityState.State.LOST;
                capabilities.reason = "transport_lost";
                acknowledged = false;
            }
            return;
        }
        if (capabilities.state == ServerCapabilityState.State.CLIENT_ONLY
                && capabilities.reason.equals("channel_unavailable")) hello(tick, operations);
        if (capabilities.state == ServerCapabilityState.State.NEGOTIATING && tick >= helloDeadline)
            capabilities.reset(ServerCapabilityState.State.CLIENT_ONLY, "negotiation_timeout");
        if (capabilities.state == ServerCapabilityState.State.READY && !acknowledged && tick >= controlRetryTick) {
            if (controlAttempts < 5) transmitControl(tick);
            else {
                capabilities.state = ServerCapabilityState.State.DENIED;
                capabilities.reason = "control_ack_timeout";
            }
        }
    }

    boolean receive(JsonObject envelope, long receivedConnection) {
        if (receivedConnection != connection) return false;
        String kind = ServerCapabilityState.text(envelope, "kind");
        if (kind.equals("welcome")) {
            if (!nonce.equals(ServerCapabilityState.text(envelope, "clientNonce"))
                    || capabilities.state != ServerCapabilityState.State.NEGOTIATING) return true;
            String status = ServerCapabilityState.text(envelope, "status");
            if (!status.equals("succeeded") && !status.equals("ok") && !status.equals("ready")) {
                capabilities.reset(ServerCapabilityState.State.DENIED,
                        ServerCapabilityState.text(envelope, "code"));
                return true;
            }
            if (!dimension.equals(ServerCapabilityState.text(envelope, "dimension"))) return true;
            try {
                if (!envelope.has("bootstrap") || envelope.get("bootstrap").getAsInt() != 1)
                    throw new IllegalArgumentException("unsupported bootstrap version");
                capabilities.welcome(envelope, connection, dimension);
                sendControl();
            } catch (RuntimeException malformed) {
                capabilities.reset(ServerCapabilityState.State.DENIED, "invalid_welcome");
            }
            return true;
        }
        if (kind.equals("control") && matchesScope(envelope)) {
            if (!envelope.has("controlGeneration")
                    || envelope.get("controlGeneration").getAsLong() != wireGeneration) return true;
            acknowledged = ServerCapabilityState.text(envelope, "status").equals("succeeded")
                    && envelope.has("allowed") && envelope.get("allowed").getAsBoolean() == allowed;
            if (!acknowledged) {
                capabilities.state = ServerCapabilityState.State.DENIED;
                capabilities.reason = "control_rejected";
            }
            return true;
        }
        return false;
    }

    void control(long authority, boolean allowed) {
        if (this.authority == authority && this.allowed == allowed) return;
        this.authority = authority;
        this.allowed = allowed;
        acknowledged = false;
        if (capabilities.scope != null) sendControl();
    }

    private void sendControl() {
        wireGeneration = Math.incrementExact(wireGeneration);
        acknowledged = false;
        controlAttempts = 0;
        transmitControl(0);
    }

    private void transmitControl(long tick) {
        controlAttempts++;
        controlRetryTick = tick + 20;
        JsonObject envelope = scoped("control");
        envelope.addProperty("controlGeneration", wireGeneration);
        envelope.addProperty("allowed", allowed);
        try { send(envelope); } catch (RuntimeException ignored) { /* same generation is safe to retry */ }
    }

    boolean mutationPermitted() {
        return allowed && acknowledged && capabilities.state == ServerCapabilityState.State.READY;
    }

    boolean send(JsonObject envelope) {
        if (!available.getAsBoolean()) return false;
        boolean sent = sender.test(envelope.deepCopy());
        if (sent && matchesScope(envelope)) lastScopeActivityTick = observedTick;
        return sent;
    }

    boolean idleRenewalDue(long tick) {
        return capabilities.state == ServerCapabilityState.State.READY
                && tick - lastScopeActivityTick >= Math.max(1, capabilities.limit("retentionTicks", 6000, 72000) - 100);
    }

    boolean canQuery(ServerCapabilityState.Scope scope) {
        return scope != null && scope.connection() == connection && available.getAsBoolean();
    }

    void disconnect() {
        connection = -1;
        binding = -1;
        allowed = false;
        acknowledged = false;
        nonce = "";
        capabilities.reset(ServerCapabilityState.State.DISCONNECTED, "disconnected");
    }

    void close() {
        if (capabilities.scope != null && available.getAsBoolean()) {
            try { send(scoped("close")); } catch (RuntimeException ignored) { /* receipts stay unresolved */ }
        }
    }

    private boolean matchesScope(JsonObject envelope) {
        return capabilities.scope != null
                && capabilities.scope.sessionId().equals(ServerCapabilityState.text(envelope, "sessionId"))
                && dimension.equals(ServerCapabilityState.text(envelope, "dimension"));
    }

    private JsonObject scoped(String kind) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("kind", kind);
        envelope.addProperty("bootstrap", 1);
        envelope.addProperty("sessionId", capabilities.scope.sessionId());
        envelope.addProperty("dimension", capabilities.scope.dimension());
        return envelope;
    }
}
