package org.maiwithu.maicraft.agent.tool;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 工具的全部契约。描述面(name/description/parameterSchema)和执行面都由
 * 这个单 Mod 接口拥有。绝大多数工具是<b>身体工具</b>
 * (动同伴的身体或读它的世界),那就是默认形态:什么都不覆写,调用自动
 * 发往服务端活体,你只实现 {@link #onGameCall}——当场回结果(查询),
 * 或经 {@code TaskDispatch.enqueue}/{@code dispatchAsync} 交给任务队列。
 *
 * <p>少数工具不走身体(纯客户端逻辑或自带协议调外部服务),
 * 覆写 {@link #invoke(ToolCall)} 自便——引擎只认 invoke,对工具怎么干活
 * 保持全盲。
 */
public interface MaiCraftTool {

    /** 稳定工具名，也是 MCP 工具注册名。 */
    String name();

    /** 给 MCP 客户端看的语义能力说明。 */
    String description();

    /** JSON Schema 兼容的参数声明。 */
    Map<String, Object> parameterSchema();

    /**
     * 工具在请求里的<b>驻留方式</b>。判据是"每轮都用不用",不是"重不重要":
     * 动作动词({@code mine}/{@code build}/{@code craft})是一次性派发,派完靠
     * {@code task_status} 轮询,调用频次其实很低;真正每轮都要的是感知与轮询。
     *
     * <p>缺省 {@link Residency#DEFERRED} —— 新工具默认不占每轮的位置,要常驻得自己
     * 表态。反过来(默认常驻)的话,忘了表态的工具会悄悄挤进每一轮请求。
     */
    default Residency residency() {
        return Residency.DEFERRED;
    }

    /** 见 {@link #residency()}。 */
    enum Residency {
        /** 完整定义每轮随请求发出。 */
        RESIDENT,
        /** 不进入公开 MCP 表面，只供 Mod 内部语义适配器调用。 */
        DEFERRED
    }

    /**
     * Run this tool for one call — the engine's ONLY entry point. 默认实现是
     * 身体工具的运输:把调用发往服务端并停靠,等 {@link #onGameCall} 的
     * 结果回家。不走身体的工具覆写它,想怎么干怎么干(当场完成、去异步、
     * 发自己的包),最后经 {@link ToolCall} 报结果。
     */
    default void invoke(ToolCall call) {
        LocalToolDispatcher.ship(call);
    }

    /**
     * 身体工具在服务端的入口,拿到活体与回信口。当场回(查询),或建
     * {@code TaskRecord} 交 {@code TaskDispatch.enqueue}(同步短活)/
     * {@code dispatchAsync}(异步长跑)。只有覆写了 {@link #invoke} 的
     * 非身体工具可以不管它——默认实现兜底出一条清晰的失败。
     */
    default void onGameCall(String toolCallId, JsonObject args,
                             LocalPlayer companion, Consumer<String> reply) {
        reply.accept(TaskResult.fail(
                "tool '" + name() + "' has no server-side body implementation").toJson());
    }
}
