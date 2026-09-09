package org.maiwithu.maicraft.agent.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.function.Consumer;

/**
 * 保存一次内部工具调用：编号、工具名、原始参数、玩家身份和结果回调。
 * 它本身不会执行任务、计时或重试；这些事情由分发器及实际工具负责。
 */
public final class ToolCall {

    private final String id;
    private final String toolName;
    private final String rawArgs;
    private final ToolAnchor ctx;
    private final Consumer<String> completion;   // 工具完成后，把结果交给这个回调

    public ToolCall(String id, String toolName, String rawArgs, ToolAnchor ctx,
                    Consumer<String> completion) {
        this.id = id;
        this.toolName = toolName;
        this.rawArgs = rawArgs;
        this.ctx = ctx;
        this.completion = completion;
    }

    /** 本次调用的编号，用于把结果与原请求对应起来。 */
    public String id() { return id; }

    public String toolName() { return toolName; }

    /** 指明这次调用针对哪个玩家；这里只要求提供 UUID。 */
    public ToolAnchor ctx() { return ctx; }

    /** 调用者传来的原始参数文本，尚未解析和检查。 */
    public String rawArgs() { return rawArgs; }

    /** 空文本按空对象处理；其他文本必须能解析成 JSON 对象。这里不检查工具的具体参数。 */
    public JsonObject args() {
        if (rawArgs == null || rawArgs.isBlank()) return new JsonObject();
        try {
            return JsonParser.parseString(rawArgs).getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid arguments JSON: " + ex.getMessage());
        }
    }

    /** 直接调用结果回调；本对象不负责防止重复回复，也不负责把回调切换到指定线程。 */
    public void complete(String resultJson) {
        completion.accept(resultJson);
    }
}
