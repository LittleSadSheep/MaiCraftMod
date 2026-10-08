// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.serverlink;

/** 客户端已登记的操作登记：操作编号、版本与是否修改世界。 */
public record ClientOperation(String id, int version, boolean mutating) {
    public ClientOperation {
        if (id == null || !id.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+"))
            throw new IllegalArgumentException("a namespaced operation id is required");
        if (version < 1) throw new IllegalArgumentException("operation version must be positive");
    }
}
