// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import net.minecraft.client.Minecraft;

/**
 * 加载器把客户端事件转交给公共代码的接收端。
 * 加载器入口只做"收到事件 → 调这里一个方法"，不写任何业务；
 * 需要新的客户端事件时，先在这里加方法，再在 Fabric 与 NeoForge 的入口各接一行。
 */
public interface ClientLifecycle {

    /** 客户端启动完成、主线程可以安全访问游戏对象之后调用一次：创建内核与能力，并开放 MCP 入口。 */
    void started();

    /** 每个客户端刻结束时调用：核对角色控制权、写本刻输入，推进等待中的世界扫描，并推进与服务端的会话。 */
    void tickEnd(Minecraft minecraft);

    /** 客户端即将退出时调用：收尾任务、落盘记忆、关闭 MCP 入口。 */
    void stopping();

    /** 收到服务端 MaiCraft 的信封（欢迎包、请求结果、服务端确认）；解析与处理转交客户端线程。 */
    void serverLinkReceived(Minecraft minecraft, String json);

    /** 玩家离开世界：清掉这台服务器已确认的证据，下一台服务器重新握手。 */
    void serverLinkDisconnected(Minecraft minecraft);
}
