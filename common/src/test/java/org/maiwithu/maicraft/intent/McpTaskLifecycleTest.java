package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.mcp.MaiCraftRuntimeFacade;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 从 MCP 运行入口验证重试和恢复取消，确认查询旧任务不会重新抢占玩家或启动旧动作。 */
public final class McpTaskLifecycleTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        repeatedExecutionLeavesHumanControlAlone();
        restoredCancellationPreservesCurrentWork();
        System.out.println("McpTaskLifecycleTest: passed");
    }

    private static void repeatedExecutionLeavesHumanControlAlone() throws Exception {
        try (var f = new Fixture()) {
            // 原请求已经结束，玩家重新接手后网络再次提交相同请求，不应产生新的接管请求。
            var original = f.record();
            original.terminal(TaskState.SUCCESS, TaskResult.ok("已等到条件"), 1);
            f.tasks().put(original.externalId(), original);
            f.requestKeys().put("same-request", original.externalId());
            var args = new JsonObject();
            args.add("goal", f.goal.toJson());
            args.addProperty("request_key", "same-request");
            var result = f.facade.execute(args).toCompletableFuture().join().getAsJsonObject();
            check(result.get("task_id").getAsString().equals(original.externalId().toString()), "重试应返回原任务编号");
            check(!ClientRuntime.actor().automationControlRequested(), "查询原请求不能重新接管玩家");
            check(result.get("control_status").getAsString().equals("not_requested"), "重试回复不能声称已请求接管");
            check(f.tasks().size() == 1, "重试不能登记第二件任务");

            // 保留原有目标校验：使用旧请求编号不能绕过能力参数检查，也不能因此申请身体。
            args.getAsJsonObject("goal").getAsJsonObject("parameters").addProperty("slot", 2);
            try {
                f.facade.execute(args).toCompletableFuture().join();
                throw new AssertionError("旧请求编号绕过了目标校验");
            } catch (CompletionException expected) {
                check(expected.getCause() instanceof IllegalArgumentException, "应报告参数错误");
            }
            check(!ClientRuntime.actor().automationControlRequested(), "无效重试也不能接管玩家");
        }
    }

    private static void restoredCancellationPreservesCurrentWork() throws Exception {
        try (var f = new Fixture()) {
            // 世界里已有另一件当前任务；从磁盘恢复的旧任务只是一张暂停记录，不占身体槽。
            var active = new TaskRecord("test_active", "", TaskRecord.NO_DEADLINE) { };
            CompanionTickDispatcher.submitCurrent(f.world.player, active);
            var restored = IntentTaskRecord.restored(UUID.randomUUID(), null, f.goal, f.identity.key(),
                    List.of(f.goal), 0, List.of(), Map.of(), Map.of(), List.of(), null, null, null, 2);
            f.tasks().put(restored.externalId(), restored);
            var args = new JsonObject();
            args.addProperty("action", "cancel");
            args.addProperty("task_id", restored.externalId().toString());
            var result = f.facade.task(args).toCompletableFuture().join().getAsJsonObject();
            check(result.get("state").getAsString().equals("cancelled"), "恢复记录应可直接取消");
            check(restored.terminalSnapshot() != null && !restored.paused(), "取消应清除暂停并留下终态");
            check(CompanionTickDispatcher.current() == active && active.getState() == TaskState.RUNNING,
                    "取消旧恢复记录不能替换或停止当前任务");
            check(!ClientRuntime.actor().automationControlRequested(), "取消记录不需要身体接管");
            check(f.world.blockUses() == 0 && f.world.itemUses() == 0, "取消不能启动旧的原生动作");

            // 重启检查点必须保存取消结果，而不能下次又把这张单子恢复成待继续状态。
            var saved = IntentStateCodec.encode(f.identity.key(), List.of(), f.tasks().values(), Map.of(), List.of());
            check(saved.getAsJsonArray("tasks").get(0).getAsJsonObject().getAsJsonObject("terminal")
                    .get("state").getAsString().equals("cancelled"), "检查点应保存已取消的终态");
            long cursor = f.runtime.attentionCheckpoint().get("cursor").getAsLong();
            try {
                f.facade.task(args).toCompletableFuture().join();
                throw new AssertionError("结束的任务再次被取消");
            } catch (CompletionException expected) {
                check(expected.getCause() instanceof IllegalStateException, "重复取消应报告已经结束");
            }
            check(f.runtime.attentionCheckpoint().get("cursor").getAsLong() == cursor, "重复取消不能发布第二次终态");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final InteractionWorldTestHarness world = new InteractionWorldTestHarness();
        final Goal goal = new Goal("maicraft:wait_for_condition", "等到条件满足", null,
                "{}", "{}", List.of(), List.of());
        final IntentRuntime runtime;
        final MaiCraftRuntimeFacade facade;
        final StateIdentity identity;
        final Map<Field, Object> dispatcher = new LinkedHashMap<>();

        Fixture() throws Exception {
            // 只构造协议入口所需的多人身份，不连接服务器，也不读取玩家真实存档。
            Minecraft client = Minecraft.getInstance();
            field(client.getClass(), "gameDirectory").set(client, Files.createTempDirectory("mcp-task-lifecycle-").toFile());
            field(world.player.connection.getClass(), "serverData").set(world.player.connection,
                    new ServerData("test", "lifecycle.invalid", ServerData.Type.OTHER));
            identity = StateIdentity.resolve(client).orElseThrow();
            var constructor = IntentRuntime.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            runtime = constructor.newInstance();
            field(IntentRuntime.class, "stateIdentity").set(runtime, identity);
            field(IntentRuntime.class, "bodyAttached").set(runtime, true);
            var facadeConstructor = MaiCraftRuntimeFacade.class.getDeclaredConstructor();
            facadeConstructor.setAccessible(true);
            facade = facadeConstructor.newInstance();
            field(MaiCraftRuntimeFacade.class, "intents").set(facade, runtime);
            Object body = ClientRuntime.requireContext(world.player).body();
            field(body.getClass(), "automationRequested").set(body, false);
            for (String name : List.of("brain", "boundPlayer", "boundLevel", "expectedHandoff", "pendingHandoff")) {
                Field slot = field(CompanionTickDispatcher.class, name);
                dispatcher.put(slot, slot.get(null));
                slot.set(null, null);
            }
        }

        IntentTaskRecord record() { return new IntentTaskRecord(UUID.randomUUID(), null, goal, identity.key()); }
        @SuppressWarnings("unchecked") Map<UUID, IntentTaskRecord> tasks() throws Exception {
            return (Map<UUID, IntentTaskRecord>) field(IntentRuntime.class, "tasks").get(runtime);
        }
        @SuppressWarnings("unchecked") Map<String, UUID> requestKeys() throws Exception {
            return (Map<String, UUID>) field(IntentRuntime.class, "requestKeys").get(runtime);
        }
        @Override public void close() throws Exception {
            // 恢复此前的调度器与身体，避免这组测试的暂停记录影响后面的游戏场景。
            for (var saved : dispatcher.entrySet()) saved.getKey().set(null, saved.getValue());
            world.close();
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Field result = type.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
