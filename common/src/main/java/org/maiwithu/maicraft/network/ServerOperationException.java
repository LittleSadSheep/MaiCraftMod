// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

/** A handler may use this only when preflight guarantees no mutation was attempted. */
public final class ServerOperationException extends RuntimeException {
    private final String code;

    private ServerOperationException(String code, String message) {
        super(message);
        if (code == null || !code.matches("[a-z][a-z0-9_]{0,63}"))
            throw new IllegalArgumentException("Invalid rejection code");
        this.code = code;
    }

    public static ServerOperationException notApplied(String code, String message) {
        return new ServerOperationException(code, message);
    }

    public String code() { return code; }
}
