// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonObject;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 按工具名把调用交给对应的工具，并把工具抛出的已知异常换成统一的错误种类。
 *
 * <p>工具调用永远得到一个统一格式的结果：程序出错也只是 {@code internal_error}，
 * 不会让 MCP 连接收到一个没有说明的失败。
 */
public final class ToolDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(ToolDispatcher.class);

    private final Map<String, McpTool> tools = new LinkedHashMap<>();
    private final RecentToolCalls recentCalls;

    public ToolDispatcher(List<McpTool> list) {
        this(list, new RecentToolCalls());
    }

    /** @param recentCalls 每次调用进来、结束都记在这里，调试面板读它看 LLM 发了什么 */
    public ToolDispatcher(List<McpTool> list, RecentToolCalls recentCalls) {
        this.recentCalls = Objects.requireNonNull(recentCalls, "recentCalls");
        for (McpTool tool : list) {
            if (tools.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("工具 " + tool.name() + " 重复登记");
            }
        }
    }

    /** 这个工具已经有行为（没接上的工具由传输层回答"还没接入"）。 */
    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /** 调用一个已接上的工具；进来和结束都记进最近的调用，面板上看得到 LLM 发了什么、成没成。 */
    public JsonObject call(String name, JsonObject arguments) {
        McpTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("工具 " + name + " 没有接上");
        }
        long callId = recentCalls.started(name, arguments);
        JsonObject reply = null;
        try {
            reply = dispatch(name, tool, arguments);
            return reply;
        } finally {
            // 连 Error 这类没接住的情况也要结账：不然这次调用会一直挂成"处理中"，面板就误以为宿主还在等。
            recentCalls.ended(callId, reply);
        }
    }

    private JsonObject dispatch(String name, McpTool tool, JsonObject arguments) {
        try {
            return tool.call(arguments == null ? new JsonObject() : arguments);
        } catch (ClientThread.NotInWorld exception) {
            return ToolReply.error(ErrorCode.NOT_IN_WORLD, "角色不在世界里：进入世界后再试");
        } catch (ClientThread.Busy exception) {
            return ToolReply.error(ErrorCode.BUSY, exception.getMessage());
        } catch (GoalRunTable.UnknownGoalRun exception) {
            return ToolReply.error(ErrorCode.UNKNOWN_ID, exception.getMessage());
        } catch (GoalRunTable.WrongGoalRunState exception) {
            return ToolReply.error(ErrorCode.INVALID_PARAMETER, exception.getMessage());
        } catch (RuntimeException exception) {
            LOG.warn("MCP 工具 {} 出错", name, exception);
            return ToolReply.error(ErrorCode.INTERNAL_ERROR, "工具 " + name + " 出错：" + exception.getClass().getSimpleName()
                    + (exception.getMessage() == null ? "" : "：" + exception.getMessage()));
        }
    }
}
