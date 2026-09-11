// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

/** Local knowledge of a wire contract; fallback must implement this exact operation/version. */
public record ClientOperation(String id, int version, boolean mutating, ClientFallback fallback) {
    public ClientOperation {
        if (id == null || !id.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+"))
            throw new IllegalArgumentException("a namespaced operation id is required");
        if (version < 1) throw new IllegalArgumentException("operation version must be positive");
    }
}
