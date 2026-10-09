// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import net.minecraft.client.Minecraft;

/**
 * 取一份现状快照。启动层实现：把 MCP 入口、和服务端的会话、控制权、目标运行表、控制循环与任务事件此刻的值
 * 抄进 {@link StatusSnapshot}。面板手里只有这一个口子，拿不到任何能改状态的对象。只在客户端线程调用。
 */
public interface StatusReader {

    /** 这一刻的现状；客户端对象由面板交进来，启动层不必自己去取。 */
    StatusSnapshot read(Minecraft minecraft);
}
