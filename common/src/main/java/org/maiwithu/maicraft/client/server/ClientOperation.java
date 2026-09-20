// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

/** 登记客户端已知的协议契约；回退实现必须对应同一操作及版本。 */
public record ClientOperation(String id, int version, boolean mutating, ClientFallback fallback) {
    public ClientOperation {
        if (id == null || !id.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+"))
            throw new IllegalArgumentException("a namespaced operation id is required");
        if (version < 1) throw new IllegalArgumentException("operation version must be positive");
    }
}
