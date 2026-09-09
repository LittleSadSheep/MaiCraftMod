package org.maiwithu.maicraft.agent.tool;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Mod 内部每种工具都要提供名字、用途、参数格式和实际做法。
 * 例如移动工具拿到坐标后创建移动任务；查询工具读取当前玩家看到的信息后直接回复。
 * 默认 invoke 会转交本地客户端分发器，不是向独立的服务端同伴发送命令。
 */
public interface MaiCraftTool {

    /** 内部查找用的名字，如 goto；对外 MCP 入口由 PublicToolCatalog 另行定义。 */
    String name();

    /** 说明这个内部工具能做什么；不会因此自动公开给 MCP 客户端。 */
    String description();

    /** 历史参数 Schema 声明；当前生产分发没有读取它，实际校验由公开契约与各执行入口完成。 */
    Map<String, Object> parameterSchema();

    /** 工具的历史分类标签。注册表可以按它筛选，但目前公开的四个 MCP 入口不由它决定。 */
    default Residency residency() {
        return Residency.DEFERRED;
    }

    /** 见 {@link #residency()}。 */
    enum Residency {
        /** 标为常用工具；是否展示由调用方决定。 */
        RESIDENT,
        /** 不进入公开 MCP 表面，只供 Mod 内部语义适配器调用。 */
        DEFERRED
    }

    /** 把调用交给本地分发器，由它安排到客户端线程执行，再把结果交回调用者。 */
    default void invoke(ToolCall call) {
        LocalToolDispatcher.ship(call);
    }

    /**
     * 用当前本地玩家执行工具。查询可以立即回复；采矿等长任务先建任务单，完成后再回复。
     * 语义任务 IntentTask 也会直接调用这里，所以 invoke 并不是所有调用必经的入口。
     * 没有提供具体实现时回复失败；下面错误字符串仍保留了旧的 server-side 用词。
     */
    default void onGameCall(String toolCallId, JsonObject args,
                             LocalPlayer companion, Consumer<String> reply) {
        reply.accept(TaskResult.fail(
                "tool '" + name() + "' has no server-side body implementation").toJson());
    }
}
