// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import net.minecraft.server.MinecraftServer;

/**
 * 加载器把服务端事件转交给公共代码的接收端。服务端只提供确认信息，不替角色动手：
 * 记录方块是谁放的、确认交互结果、读取模组数据。
 * 独立服务器和单人游戏的内置服务器都会走这里，因此不能引用任何仅客户端的类。
 */
public interface ServerLifecycle {

    /** 每个服务端刻结束时调用：推进方块归属记录与模组数据读取。 */
    void tickEnd(MinecraftServer server);

    /** 服务器停止后调用：结束与客户端的会话并清理服务端状态。 */
    void stopped(MinecraftServer server);
}
