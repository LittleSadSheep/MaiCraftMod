package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * MCP 网络服务通过这个接口调用游戏里的功能，不自己另建一套玩家任务。
 * 网络请求可能在其他线程上到达，具体实现必须把读取和修改游戏状态的工作交给客户端主线程。
 */
public interface RuntimeFacade {
    /** 网络等回复超时时，要分清请求还没开始、只是在等消息，还是已经开始改变任务／世界。 */
    enum CancellationDisposition {
        CANCELLED_BEFORE_START,
        CANCELLED_WHILE_WAITING,
        ALREADY_STARTED,
        SETTLED
    }

    /** 让网络层询问这次调用能否撤回；撤回请求与取消整个游戏任务是两件事。 */
    interface ManagedCall {
        CancellationDisposition cancelCall();
    }

    /** 查看状态或等待消息，返回值表示之后会到达的回复。 */
    CompletionStage<JsonElement> perceive(JsonObject arguments);

    /** 检查并登记计划，不立即执行其中的身体动作。 */
    CompletionStage<JsonElement> plan(JsonObject arguments);

    /** 启动目标并返回任务编号；长时间的动作由游戏每刻继续推进。 */
    CompletionStage<JsonElement> execute(JsonObject arguments);

    /** 查询、暂停、恢复、取消任务，或回答任务提出的问题。 */
    CompletionStage<JsonElement> task(JsonObject arguments);

    CompletionStage<JsonElement> readAttention();

    /** 读取知识文档；默认实现只读离线目录，不要求玩家先进入世界。 */
    default CompletionStage<JsonElement> knowledge(JsonObject arguments) {
        return java.util.concurrent.CompletableFuture.completedFuture(
                org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary.offline().request(arguments));
    }

    /** 登记“有新消息时叫我”的回调；关闭返回对象时只移除这一个订阅者，重复关闭也应安全。 */
    AutoCloseable subscribeAttention(Consumer<JsonElement> listener);
}
