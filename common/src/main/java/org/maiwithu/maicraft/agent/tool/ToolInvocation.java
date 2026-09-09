package org.maiwithu.maicraft.agent.tool;

/**
 * 一条待调用工具的基本数据：编号、名字和原始 JSON 参数。
 * 它只保存数据，没有解析参数、执行动作或返回结果的逻辑。
 */
@org.maiwithu.maicraft.api.Internal
public record ToolInvocation(String id, String name, String argsJson) {}
