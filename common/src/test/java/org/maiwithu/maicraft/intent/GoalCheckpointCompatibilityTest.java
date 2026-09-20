package org.maiwithu.maicraft.intent;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.intent.persistence.IntentStateStore;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 收紧新目标的参数后，仍可恢复旧计划、任务和失败历史，不把整个世界的检查点封锁。 */
public final class GoalCheckpointCompatibilityTest {
    public static void main(String[] args) throws Exception {
        verify(new Goal("maicraft:wait_for_condition", "旧版等待", null,
                "{\"after_s\":1.5}", "{}", List.of(), List.of()));
        verify(new Goal("maicraft:acquire_items", "旧版取物数量", null,
                "{\"item_id\":\"minecraft:dirt\",\"count\":1.5}", "{}", List.of(), List.of()));
        verify(new Goal("maicraft:acquire_items", "旧版取物地点",
                new Goal.SemanticTarget("landmark", "营地", null, null),
                "{\"item_id\":\"minecraft:dirt\"}", "{}", List.of(), List.of()));
        System.out.println("GoalCheckpointCompatibilityTest: passed");
    }

    private static void verify(Goal goal) throws Exception {
        var constructor = IntentRuntime.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var runtime = constructor.newInstance();
        var identity = new StateIdentity("7".repeat(64), Files.createTempDirectory("legacy-goal-"));
        field("stateIdentity").set(runtime, identity);
        var store = (IntentStateStore) field("stateStore").get(runtime);
        var plan = Plan.compile(goal, 0);
        var original = new IntentTaskRecord(UUID.randomUUID(), plan.id(), goal, identity.key());
        original.addAttempt(new IntentTaskRecord.AttemptSnapshot(0, goal, TaskState.FAILED,
                "earlier attempt", TaskResult.fail("earlier attempt").toJson(), 1));
        store.saveAsync(identity, IntentStateCodec.encode(identity.key(), List.of(plan), List.of(original),
                Map.of("old-goal", original.externalId()), List.of())).join();

        // 用真实恢复入口读回三类历史；不能为了通过新校验而改写当初的请求内容。
        var restore = IntentRuntime.class.getDeclaredMethod("restoreBound", long.class);
        restore.setAccessible(true);
        restore.invoke(runtime, 100L);
        runtime.requireRecoveredState();
        var restored = runtime.task(original.externalId());
        check(restored != null && restored.paused() && restored.restoredDetached(), "旧目标应以暂停状态恢复");
        check(restored.goal().parameters().equals(goal.parameters()) && restored.attempts().size() == 1,
                "保留原请求和失败历史");
        check(runtime.plan(plan.id()) != null && runtime.taskForRequestKey("old-goal") == restored,
                "旧计划和请求编号仍可查询");
        try {
            runtime.compile(goal, 101);
            throw new AssertionError("历史兼容绕过了新请求的数量或地点校验");
        } catch (SemanticContractException expected) { }
        // 不想继续的旧任务可以直接取消，取消不要求把无效参数重新解释为另一项行动。
        field("bodyAttached").set(runtime, true);
        runtime.cancelRestored(restored, 102);
        check(restored.getState() == TaskState.CANCELLED, "恢复的旧目标仍可取消");
    }

    private static Field field(String name) throws Exception {
        Field field = IntentRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
