// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.serverlink;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;

/**
 * 只读操作入口的生产实现：问的是当前连接上的服务端会话。答不答看握手协商到的能力清单，
 * 问出去的请求由会话的请求路由排队、逐刻分发、跟踪结果；这里只是把三样包成联动用得上的样子。
 */
public final class SessionServerReads implements ServerReads {

    private final ServerLinkSession session;

    public SessionServerReads(ServerLinkSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    @Override public boolean available(String operationId) {
        ServerCapabilityState capabilities = session.capabilities();
        if (capabilities.state() != ServerCapabilityState.State.READY) return false;
        ServerCapabilityState.Feature feature = capabilities.features().get(operationId);
        return feature != null && feature.enabled() && !feature.mutating();
    }

    @Override public Optional<UUID> ask(String operationId, JsonObject body) {
        if (!available(operationId)) return Optional.empty();
        try {
            return Optional.of(session.router().submit(operationId, body, false).id());
        } catch (IllegalArgumentException notRegistered) {
            // 客户端没登记这个操作（联动入口没声明）：当作答不了，不让读数变成内部错误。
            return Optional.empty();
        }
    }

    @Override public Optional<ClientRequest.Snapshot> answer(UUID request) {
        return session.router().poll(request);
    }
}
