package org.maiwithu.maicraft.agent.tool;

/**
 * 一条待调用工具的基本数据：编号、名字和原始 JSON 参数。
 * 当前生产和测试没有引用这个旧记录；实际调用使用 ToolCall。它没有执行或返回结果的逻辑。
 */
@org.maiwithu.maicraft.api.Internal
public record ToolInvocation(String id, String name, String argsJson) {}
