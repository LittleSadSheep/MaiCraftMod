// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import org.maiwithu.maicraft.network.ServerOperationException;
import org.maiwithu.maicraft.server.ServerOperationRegistry;

/**
 * 服务端联动登记表：收服务端联动入口登记的只读操作（某格所在 ME 网络的摘要、应力网络摘要、机器的权威读数），
 * 交给服务端操作注册表，握手时和客户端协商。一律只读：服务端只回答事实，不替角色动手，也不列箱子里的东西。
 *
 * <p>一个入口交接到一半出错时，它登记的操作一个都不进注册表，不留下半个模组。
 * 每个操作包一层：模组停用或碰到接口对不上时，按"没执行"回答客户端并写明原因，客户端如实说读不到。
 */
public final class ServerCompatRegistry {

    private final ServerOperationRegistry operations;
    private final List<ServerCompatModule> modules = new ArrayList<>();
    /** 正在交接的入口与它登记的操作：交接成功、核对过不撞名才进注册表。 */
    private ServerCompatModule current;
    private List<Pending> pending;

    private record Pending(String operationId, int version, BiFunction<ServerPlayer, JsonObject, JsonObject> handler) {}

    public ServerCompatRegistry(ServerOperationRegistry operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    /**
     * 让一个入口交接：入口在 contribute 里登记操作；交接出错时撤掉它登记的全部操作并把错误抛给调用方写日志。
     */
    public void add(ServerCompatModule module) {
        Objects.requireNonNull(module, "module");
        current = module;
        pending = new ArrayList<>();
        try {
            module.contribute(this);
            // 先全部核对再登记：撞了已有的操作名就整个入口不登记，不留下登记了一半的操作。
            Set<String> seen = new HashSet<>();
            for (Pending operation : pending) {
                if (operations.registered(operation.operationId()) || !seen.add(operation.operationId())) {
                    throw new IllegalStateException("服务端操作 " + operation.operationId() + " 已经有人登记了");
                }
            }
            for (Pending operation : pending) {
                operations.register(operation.operationId(), operation.version(), false,
                        guarded(module, operation.operationId(), operation.handler()));
            }
            modules.add(module);
        } finally {
            current = null;
            pending = null;
        }
    }

    /**
     * 联动入口登记一个只读操作。只能在 contribute 里调。
     *
     * @param operationId 操作名，按"模组.读什么"起，例如 ae2.network
     * @param version     操作的版本，改了请求或回答的形状就加一
     * @param handler     回答：收客户端发来的请求体，交回一份只读的回答；碰模组的每一下经入口的 call
     */
    public void readOperation(ServerCompatModule module, String operationId, int version,
            BiFunction<ServerPlayer, JsonObject, JsonObject> handler) {
        if (pending == null || module != current) {
            throw new IllegalStateException("只读操作只能由正在交接的联动入口在 contribute 里登记：" + operationId);
        }
        pending.add(new Pending(Objects.requireNonNull(operationId, "operationId"), version,
                Objects.requireNonNull(handler, "handler")));
    }

    /** 登记了的服务端联动入口，按清单顺序。 */
    public List<ServerCompatModule> modules() {
        return Collections.unmodifiableList(modules);
    }

    // 包一层：停用了、或这一下碰到接口对不上，都按"没执行"回答，客户端如实说读不到，不当成服务端出错。
    static BiFunction<ServerPlayer, JsonObject, JsonObject> guarded(ServerCompatModule module,
            String operationId, BiFunction<ServerPlayer, JsonObject, JsonObject> handler) {
        return (player, body) -> {
            if (!module.active()) {
                throw ServerOperationException.notApplied("mod_disabled",
                        module.name() + "的服务端联动已停用：" + module.disabledReason().orElse("原因不明"));
            }
            try {
                return handler.apply(player, body);
            } catch (ServerModApiMismatch broken) {
                throw ServerOperationException.notApplied("mod_api_mismatch", broken.getMessage());
            }
        };
    }
}
