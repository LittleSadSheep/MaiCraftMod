package org.maiwithu.maicraft.intent;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.base.NativeSubmissionTaskRecord;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.intent.persistence.IntentStateStore;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.task.TaskState;

/** 走真实语义父任务的聊天绑定和检查点恢复，核对任务身份先落盘，旧操作恢复后无法再次取得发送许可。 */
public final class ChatDurableCheckpointTest {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var writes = new ArrayDeque<Runnable>();
        var storeConstructor = IntentStateStore.class.getDeclaredConstructor(Executor.class);
        storeConstructor.setAccessible(true);
        var store = storeConstructor.newInstance((Executor) writes::addLast);
        var runtimeConstructor = IntentRuntime.class.getDeclaredConstructor();
        runtimeConstructor.setAccessible(true);
        var runtime = runtimeConstructor.newInstance();
        var identity = new StateIdentity("c".repeat(64), Files.createTempDirectory("chat-parent-checkpoint-"));
        field(IntentRuntime.class, "stateStore").set(runtime, store);
        field(IntentRuntime.class, "stateIdentity").set(runtime, identity);
        field(IntentRuntime.class, "bodyAttached").set(runtime, true);
        var goal = new Goal("maicraft:chat", "发送一次问候", null,
                "{\"text\":\"你好\"}", "{}", List.of(), List.of());
        var original = new IntentTaskRecord(UUID.randomUUID(), null, goal, identity.key());
        var tasks = (Map<UUID, IntentTaskRecord>) field(IntentRuntime.class, "tasks").get(runtime);
        tasks.put(original.externalId(), original);
        ((Map<String, UUID>) field(IntentRuntime.class, "requestKeys").get(runtime)).put("chat-request", original.externalId());
        try (var world = new InteractionWorldTestHarness()) {
            var parent = new IntentTask(world.player, original, runtime);
            check(parent.tick(world.player) == TaskState.RUNNING, "先建立聊天子任务，不在接单时直接发消息");
            var child = child(parent);
            check(!child.prepareSubmission() && writes.size() == 1, "聊天先等待真实父任务检查点");
            UUID operation = NativeSubmissionBinding.operationId(original, "chat");
            var marker = identity.directory().resolve("chat-submissions").resolve(identity.key()).resolve(operation + ".json");
            check(!Files.exists(marker), "父任务身份未落盘时不能提前预留聊天操作");
            writes.removeFirst().run();
            awaitPermit(child);
            check(Files.exists(marker), "取得许可前应已保留聊天专属操作编号");

            // 用全新存储器读磁盘，模拟进程丢掉会话内存，但请求编号和当前步骤必须仍能恢复。
            var loaded = new IntentStateStore().load(identity);
            var saved = IntentStateCodec.decode(loaded.root());
            var snapshot = saved.tasks().getFirst();
            check(saved.requestKeys().get("chat-request").equals(original.externalId()), "保存必须包含请求去重编号");
            var restored = IntentTaskRecord.restored(snapshot.id(), snapshot.planId(), snapshot.goal(), identity.key(),
                    snapshot.steps(), snapshot.stepIndex(), snapshot.completed(), snapshot.internalPositions(),
                    snapshot.internalAreaProtections(), snapshot.attempts(), snapshot.decision(), snapshot.pendingAnswer(),
                    snapshot.terminal(), 100);
            tasks.put(restored.externalId(), restored);
            check(restored.resume(), "恢复后仍需明确继续");
            runtime.restoredTaskAttached(restored);
            var resumed = new IntentTask(world.player, restored, runtime);
            check(resumed.tick(world.player) == TaskState.RUNNING, "恢复会建立新的聊天会话");
            var retry = child(resumed);
            check(NativeSubmissionBinding.operationId(restored, "chat").equals(operation), "新会话必须沿用原操作身份");
            check(!retry.prepareSubmission(), "恢复后的父检查点仍须确认写完");
            writes.removeFirst().run();
            try {
                awaitPermit(retry);
                throw new AssertionError("恢复的旧聊天操作再次取得了发送许可");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().contains("chat_submission_already_reserved"), "应由聊天历史明确阻止重发");
            }
            check(world.blockUses() == 0 && world.itemUses() == 0, "持久身份核验不能触发其他游戏操作");
        }
        System.out.println("ChatDurableCheckpointTest: passed");
    }

    private static NativeSubmissionTaskRecord child(IntentTask parent) throws Exception {
        Object child = field(IntentTask.class, "childRecord").get(parent);
        if (!(child instanceof NativeSubmissionTaskRecord submission))
            throw new AssertionError("聊天子任务没有参与父任务的持久提交绑定");
        return submission;
    }

    private static void awaitPermit(NativeSubmissionTaskRecord child) throws InterruptedException {
        // 生产每个游戏刻检查一次；测试有限等待同一后台写入，不能以等待超时作为发送许可。
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            if (child.prepareSubmission()) return;
            Thread.sleep(1);
        }
        throw new AssertionError("聊天提交标记未在测试期限内完成");
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
