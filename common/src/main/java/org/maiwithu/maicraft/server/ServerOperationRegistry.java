// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import net.minecraft.server.level.ServerPlayer;
import org.maiwithu.maicraft.network.ServerFeature;
import org.maiwithu.maicraft.network.ServerOperationException;

/**
 * 服务端操作注册表：握手时向客户端协商哪些操作可用，执行时转到对应处理器。
 * 与加载器无关；服务器所有者的策略在协商时和执行前各检查一次。
 * 机器操作要经当前打开的容器界面执行，那个作用域约束随机器域的移植再加。
 */
public final class ServerOperationRegistry {
    private record Operation(ServerFeature feature, BiFunction<ServerPlayer, JsonObject, JsonObject> handler) {}

    private final Map<String, Operation> operations = new LinkedHashMap<>();
    private BiPredicate<ServerPlayer, String> policy = (player, operation) -> true;

    public void register(String operationId, int version, boolean mutating,
                         BiFunction<ServerPlayer, JsonObject, JsonObject> handler) {
        var descriptor = new ServerFeature(operationId, version, mutating, true, new JsonObject());
        if (operations.size() >= 32) throw new IllegalStateException("Too many registered operations");
        if (operations.putIfAbsent(operationId, new Operation(descriptor, Objects.requireNonNull(handler))) != null)
            throw new IllegalStateException("Operation already registered: " + operationId);
    }

    /** 服务器所有者策略：返回 false 的操作不参与协商，已协商的也会在执行前被拒绝。 */
    public void setPolicy(BiPredicate<ServerPlayer, String> next) {
        policy = Objects.requireNonNull(next);
    }

    public boolean allowed(ServerPlayer player, String operation) {
        return operations.containsKey(operation) && policy.test(player, operation);
    }

    List<ServerFeature> features(ServerPlayer player) {
        return operations.values().stream().map(operation -> {
            var feature = operation.feature();
            return new ServerFeature(feature.operationId(), feature.version(), feature.mutating(),
                    policy.test(player, feature.operationId()), feature.limits());
        }).toList();
    }

    JsonObject execute(ServerPlayer player, String operationId, JsonObject body) {
        if (!player.serverLevel().getServer().isSameThread())
            throw new IllegalStateException("Server operation requires the game thread");
        Operation operation = operations.get(operationId);
        if (operation == null)
            throw ServerOperationException.notApplied("unsupported_operation", "Operation is not installed");
        if (!policy.test(player, operationId))
            throw ServerOperationException.notApplied("authorization_denied", "Operation disabled by server policy");
        return Objects.requireNonNull(operation.handler().apply(player, body), "Handler returned no result");
    }
}
