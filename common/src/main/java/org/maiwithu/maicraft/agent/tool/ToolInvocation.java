package org.maiwithu.maicraft.agent.tool;

/**
 * 一条待调用工具的基本数据：编号、名字和原始 JSON 参数。
 * 当前生产和测试没有引用这个旧记录；ToolCall 也属于保留的旧分发通道，现用语义任务直接调用工具执行入口。
 */
@org.maiwithu.maicraft.api.Internal
public record ToolInvocation(String id, String name, String argsJson) {}
