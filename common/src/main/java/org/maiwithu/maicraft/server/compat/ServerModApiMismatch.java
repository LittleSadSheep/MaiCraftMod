// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat;

/**
 * 服务端碰模组时接口对不上：LinkageError 转成的普通异常，出现后这个模组的服务端联动整体停用。
 * 和客户端的 compat.ModApiMismatch 同义；分层不许服务端依赖客户端联动的包，所以各有一份。
 */
public final class ServerModApiMismatch extends RuntimeException {

    public ServerModApiMismatch(String message) {
        super(message);
    }

    public ServerModApiMismatch(String message, Throwable cause) {
        super(message, cause);
    }
}
