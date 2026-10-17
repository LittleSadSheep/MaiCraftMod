// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonObject;

/**
 * 一个 MCP 工具的行为：收到参数，返回统一格式的结果（见 {@link ToolReply}）。
 *
 * <p>在 MCP 的请求线程上调用。要读写游戏状态（目标、控制循环、世界）的部分经 {@link ClientThread}
 * 交给客户端线程做。可以抛出 {@link ClientThread.NotInWorld}、{@link ClientThread.Busy} 和目标运行表的
 * 编号与处境异常，由 {@link ToolDispatcher} 统一换成错误种类；其余异常按程序错误处理。
 */
public interface McpTool {
    /** 工具名，和工具清单里的名字一致。 */
    String name();

    /** 处理一次调用；参数为空对象时表示没给参数。 */
    JsonObject call(JsonObject arguments);
}
