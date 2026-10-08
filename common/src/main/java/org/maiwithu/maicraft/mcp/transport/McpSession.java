// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.transport;

import java.util.concurrent.atomic.AtomicBoolean;

/** 一个 MCP 客户端连接：协议版本、最近活动时间与关闭状态；不保存任何玩家或任务的数据。 */
final class McpSession {
    final String id;
    final String version;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long lastActivityNanos = System.nanoTime();

    McpSession(String id, String version) {
        this.id = id;
        this.version = version;
    }

    /** 每次请求都刷新活动时间；空闲超过时限的连接由周期回收器关闭。 */
    void touch() {
        lastActivityNanos = System.nanoTime();
    }

    long lastActivityNanos() {
        return lastActivityNanos;
    }

    boolean closed() {
        return closed.get();
    }

    boolean expired(long nowNanos, long ttlNanos) {
        return closed.get() || nowNanos - lastActivityNanos > ttlNanos;
    }

    /** 只关闭一次；已提交给游戏侧的请求不在这里取消。 */
    boolean close() {
        return closed.compareAndSet(false, true);
    }
}
