// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.serverlink;

import com.google.gson.JsonObject;

/** 加载器提供的与服务端 MaiCraft 之间的发送通道；接收由加载器转交给会话。 */
public interface LinkTransport {

    /** 当前连接是否协商出了 MaiCraft 通道；未连接或服务器没装 Mod 时为 false。 */
    boolean available();

    /** 发送一个信封；入队后抛出异常时调用方按结果不明处理。 */
    void send(JsonObject envelope);
}
