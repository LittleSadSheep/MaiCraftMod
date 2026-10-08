// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

/** 只有预检能保证尚未尝试任何修改时，处理器才可使用此异常。 */
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
