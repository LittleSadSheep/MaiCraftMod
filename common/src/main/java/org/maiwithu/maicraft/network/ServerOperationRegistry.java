// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import net.minecraft.server.level.ServerPlayer;

/** Common registration point for authoritative modules, independent of either loader. */
public final class ServerOperationRegistry {
    private record Operation(ServerFeature feature, BiFunction<ServerPlayer, JsonObject, JsonObject> handler) {}
    private static final Map<String, Operation> OPERATIONS = new LinkedHashMap<>();
    private static BiPredicate<ServerPlayer, String> policy = (player, operation) -> true;

    private ServerOperationRegistry() {}

    public static void register(String operationId, int version, boolean mutating,
                                BiFunction<ServerPlayer, JsonObject, JsonObject> handler) {
        register(operationId, version, mutating, new JsonObject(), handler);
    }

    public static synchronized void register(String operationId, int version, boolean mutating,
                                             JsonObject limits,
                                             BiFunction<ServerPlayer, JsonObject, JsonObject> handler) {
        var descriptor = new ServerFeature(operationId, version, mutating, true, limits);
        if (OPERATIONS.size() >= 32) throw new IllegalStateException("Too many registered operations");
        if (OPERATIONS.putIfAbsent(operationId, new Operation(descriptor, Objects.requireNonNull(handler))) != null)
            throw new IllegalStateException("Operation already registered: " + operationId);
    }

    /** Server owner policy is checked both at negotiation and immediately before execution. */
    public static synchronized void setPolicy(BiPredicate<ServerPlayer, String> next) {
        policy = Objects.requireNonNull(next);
    }
    public static synchronized boolean allowed(ServerPlayer player, String operation) {
        return OPERATIONS.containsKey(operation) && policy.test(player,operation);
    }

    static synchronized List<ServerFeature> features(ServerPlayer player) {
        return OPERATIONS.values().stream().map(operation -> {
            var feature = operation.feature();
            return new ServerFeature(feature.operationId(), feature.version(), feature.mutating(),
                    policy.test(player, feature.operationId()), feature.limits());
        }).toList();
    }

    static JsonObject execute(ServerPlayer player, String operationId, JsonObject body) {
        if (!player.serverLevel().getServer().isSameThread())
            throw new IllegalStateException("Server operation requires the game thread");
        Operation operation;
        synchronized (ServerOperationRegistry.class) {
            operation = OPERATIONS.get(operationId);
            if (operation == null)
                throw ServerOperationException.notApplied("unsupported_operation", "Operation is not installed");
            if (!policy.test(player, operationId))
                throw ServerOperationException.notApplied("authorization_denied", "Operation disabled by server policy");
        }
        return org.maiwithu.maicraft.server.machine.ServerMenuAccess.execute(player, body,
                () -> Objects.requireNonNull(operation.handler().apply(player, body), "Handler returned no result"));
    }
}
