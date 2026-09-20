package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.mcp.MaiCraftRuntimeFacade;
import org.maiwithu.maicraft.mcp.RuntimeFacade.CancellationDisposition;
import org.maiwithu.maicraft.mcp.RuntimeFacade.ManagedCall;
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
        fullHistoryRejectsBeforeAcceptingMoreWork();
        networkCancellationRespectsClientDispatch();
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

    private static void fullHistoryRejectsBeforeAcceptingMoreWork() throws Exception {
        try (var f = new Fixture()) {
            // 旧世界恢复了满额未完成任务时，不能再接一件随后会被检查点截掉的新工作。
            IntentTaskRecord first = null;
            for (int i = 0; i < IntentStateCodec.MAX_TASKS; i++) {
                var record = IntentTaskRecord.restored(UUID.randomUUID(), null, f.goal, f.identity.key(),
                        List.of(f.goal), 0, List.of(), Map.of(), Map.of(), List.of(), null, null, null, 1);
                if (first == null) first = record;
                f.tasks().put(record.externalId(), record);
            }
            long cursor = f.runtime.attentionCheckpoint().get("cursor").getAsLong();
            var request = new JsonObject();
            request.add("goal", f.goal.toJson());
            request.addProperty("request_key", "new-at-capacity");
            try {
                f.facade.execute(request).toCompletableFuture().join();
                throw new AssertionError("已经无法完整保存时仍然接收了新任务");
            } catch (CompletionException expected) {
                check(expected.getCause() instanceof IllegalStateException, "任务表已满应说明无法接单");
            }
            check(f.tasks().size() == IntentStateCodec.MAX_TASKS && f.requestKeys().isEmpty(),
                    "拒绝接单不能留下新任务或请求编号");
            check(f.runtime.attentionCheckpoint().get("cursor").getAsLong() == cursor,
                    "未接收的任务不能发布已经开始的通知");
            check(!ClientRuntime.actor().automationControlRequested() && CompanionTickDispatcher.current() == null,
                    "拒绝接单应撤回新接管请求，不占玩家身体");

            // 明确取消一件旧事后，只淘汰这条已结束历史，新任务和它的请求编号必须一起保存。
            f.runtime.cancelRestored(first, 2);
            var accepted = f.facade.execute(request).toCompletableFuture().join().getAsJsonObject();
            UUID id = UUID.fromString(accepted.get("task_id").getAsString());
            check(f.tasks().size() == IntentStateCodec.MAX_TASKS && f.runtime.task(id) != null,
                    "空出记录后才能接到一件可保存的新任务");
            var saved = IntentStateCodec.encode(f.identity.key(), List.of(), f.tasks().values(), f.requestKeys(), List.of());
            var decoded = IntentStateCodec.decode(saved);
            check(decoded.tasks().stream().anyMatch(task -> task.id().equals(id))
                    && id.equals(decoded.requestKeys().get("new-at-capacity")), "检查点不能丢掉刚接受的任务身份");
            // 即使未来调用者绕过接单检查，编码器也必须拒绝残缺快照，不能静默丢弃最后一条记录。
            var overflow = new ArrayList<>(f.tasks().values());
            overflow.add(f.record());
            try {
                IntentStateCodec.encode(f.identity.key(), List.of(), overflow, Map.of(), List.of());
                throw new AssertionError("编码器截断超量任务后仍然返回成功");
            } catch (IllegalArgumentException expected) { }
        }
    }

    private static void networkCancellationRespectsClientDispatch() throws Exception {
        try (var f = new Fixture()) {
            // 模拟网络线程排队到游戏线程：尚未执行的请求可撤回，撤回后不能接管或登记任务。
            var queue = new ConcurrentLinkedQueue<Runnable>();
            field(Minecraft.class, "pendingRunnables").set(Minecraft.getInstance(), queue);
            var request = new JsonObject();
            request.add("goal", f.goal.toJson());
            var pending = fromNetwork(() -> f.facade.execute(request));
            check(((ManagedCall) pending).cancelCall() == CancellationDisposition.CANCELLED_BEFORE_START,
                    "还在游戏线程队列中的请求应能撤回");
            queue.remove().run();
            check(pending.toCompletableFuture().isCancelled() && f.tasks().isEmpty()
                    && !ClientRuntime.actor().automationControlRequested(), "被撤回的排队请求不能开始游戏工作");

            // 已经开始接单时，网络取消只能报告已经开始；真正停止任务要用 task(cancel)。
            var running = new AtomicReference<CompletionStage<JsonElement>>();
            var cancellation = new AtomicReference<CancellationDisposition>();
            try (var subscription = f.runtime.subscribeAttention(event -> cancellation.compareAndSet(null,
                    ((ManagedCall) running.get()).cancelCall()))) {
                running.set(fromNetwork(() -> f.facade.execute(request)));
                queue.remove().run();
            }
            check(cancellation.get() == CancellationDisposition.ALREADY_STARTED,
                    "发布开始事件时不能把执行中的请求说成已经撤回");
            check(running.get().toCompletableFuture().join().getAsJsonObject().get("accepted").getAsBoolean()
                    && f.tasks().size() == 1, "已经开始的请求仍交付真实接单结果");
            check(((ManagedCall) running.get()).cancelCall() == CancellationDisposition.SETTLED,
                    "请求交付完成后不再撤回任何游戏工作");
        }
    }

    private static CompletionStage<JsonElement> fromNetwork(Supplier<CompletionStage<JsonElement>> request)
            throws InterruptedException {
        var result = new AtomicReference<CompletionStage<JsonElement>>();
        Thread network = new Thread(() -> result.set(request.get()), "mcp-request-test");
        network.start();
        network.join();
        return result.get();
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
            // 通过真实交还流程还原玩家输入，使“失败后撤回新接管请求”与正常游戏中的身体状态一致。
            var release = body.getClass().getDeclaredMethod("shutdown");
            release.setAccessible(true);
            release.invoke(body);
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
