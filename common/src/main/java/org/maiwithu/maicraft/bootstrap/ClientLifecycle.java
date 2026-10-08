// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

/**
 * 加载器把客户端事件转交给公共代码的接收端。
 * 加载器入口只做"收到事件 → 调这里一个方法"，不写任何业务；
 * 需要新的客户端事件时，先在这里加方法，再在 Fabric 与 NeoForge 的入口各接一行。
 */
public interface ClientLifecycle {

    /** 客户端启动完成、主线程可以安全访问游戏对象之后调用一次：创建内核与能力，并开放 MCP 入口。 */
    void started();

    /** 每个客户端刻结束时调用：按打断规则决定角色这一刻听谁的，并推进那个任务一刻。 */
    void tickEnd();

    /** 客户端即将退出时调用：收尾任务、落盘记忆、关闭 MCP 入口。 */
    void stopping();
}
