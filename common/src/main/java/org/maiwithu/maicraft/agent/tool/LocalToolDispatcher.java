package org.maiwithu.maicraft.agent.tool;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskResult;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 保留的旧工具调用通道：ship 登记 ToolCall 并切到游戏线程，deliver 按编号交回结果。
 * 当前仓库没有创建 ToolCall 的生产入口；语义任务直接调用 onGameCall，再接住任务单或即时结果。
 * 调度器仍会调用 deliver，但只有先经 ship 登记过的调用才会收到这里的回调。
 */
public final class LocalToolDispatcher {

    private static final Map<String, ToolCall> IN_FLIGHT = new ConcurrentHashMap<>();

    private LocalToolDispatcher() {}

    /** Submit one internal capability call to the Minecraft client thread. */
    public static void ship(ToolCall call) {
        // 先登记调用编号，再交给游戏线程处理；相同编号不能同时执行两次，后来的那次会被拒绝。
        if (call == null) {
            throw new IllegalArgumentException("call is required");
        }
        ToolCall previous = IN_FLIGHT.putIfAbsent(call.id(), call);
        if (previous != null) {
            call.complete(TaskResult.fail("duplicate tool call id: " + call.id()).toJson());
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Runnable invoke = () -> invokeOnClient(minecraft, call);
        if (minecraft.isSameThread()) {
            invoke.run();
        } else {
            minecraft.execute(invoke);
        }
    }

    private static void invokeOnClient(Minecraft minecraft, ToolCall call) {
        // 查信息可以马上回复；走路、挖矿等要等游戏里做完，再由调度器把结果送回来。
        try {
            LocalPlayer player = minecraft.player;
            if (player == null || minecraft.level == null || minecraft.gameMode == null) {
                deliver(call.id(), TaskResult.fail("no active local player world").toJson());
                return;
            }
            MaiCraftTool tool = ToolRegistry.resolve(call.toolName());
            if (tool == null) {
                deliver(call.id(), TaskResult.fail("unknown internal capability: " + call.toolName()).toJson());
                return;
            }
            // 这里只按名字分发，没有自动执行工具的 parameterSchema；工具自己和公开入口必须承担实际参数检查。
            tool.onGameCall(call.id(), call.args(), player, result -> deliver(call.id(), result));
        } catch (RuntimeException exception) {
            if (!(exception instanceof IllegalArgumentException)) {
                org.maiwithu.maicraft.core.Constants.LOG.error(
                        "[maicraft-tool] {} failed (call {})", call.toolName(), call.id(), exception);
            }
            deliver(call.id(), TaskResult.fail("capability failed: " + safeMessage(exception)).toJson());
        }
    }

    /** Complete a parked call exactly once. Task settlement uses this same path. */
    public static void deliver(String toolCallId, String resultJson) {
        // 先从“等待结果”的名单中删掉，再回复；这样即使重复通知结束，也只会回复一次。
        ToolCall call = IN_FLIGHT.remove(toolCallId);
        if (call != null) call.complete(resultJson);
    }

    /** Cancel the current body task and forget calls anchored to this local body. */
    public static void abort(UUID playerUuid) {
        Minecraft minecraft = Minecraft.getInstance();
        Runnable cancel = () -> {
            LocalPlayer player = minecraft.player;
            if (player != null && player.getUUID().equals(playerUuid)) {
                CompanionTickDispatcher.cancelFor(player);
            }
            forget(playerUuid);
        };
        if (minecraft.isSameThread()) cancel.run(); else minecraft.execute(cancel);
    }

    /** Forget calls from a world that is no longer active. */
    public static void forget(UUID playerUuid) {
        // 这里只删除这个玩家还在等的调用，不通知它们“已取消”；等待者不会因此收到结果。
        IN_FLIGHT.values().removeIf(call -> playerUuid.equals(call.ctx().entityUuid()));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
