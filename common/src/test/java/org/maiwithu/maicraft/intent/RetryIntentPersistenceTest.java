// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.core.integration.machine.process.NativeProcessRegistry;
import org.maiwithu.maicraft.core.task.base.NativeConsumptionJournal;
import org.maiwithu.maicraft.core.task.base.NativeSubmissionTaskRecord;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.intent.persistence.IntentStateStore;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 重试先保存真实意图再预约消费；使用真实检查点与预约文件，不启动机器、菜单或物品操作。 */
public final class RetryIntentPersistenceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Path directory = Files.createTempDirectory("retry-intent-persistence-");
        for (boolean build : new boolean[]{false, true}) revisedProcessSurvives(directory.resolve("process-" + build), build);
        forbiddenRetryPreservesGoal(directory.resolve("forbidden")); semanticTargetAndContinuation(directory.resolve("semantic"));
        System.out.println("RetryIntentPersistenceTest: actual retry intent, durable namespace and retry refusal preserved");
    }

    private static void revisedProcessSurvives(Path directory, boolean build) throws Exception {
        var h = new Harness(directory, machine(build));
        UUID original = NativeSubmissionBinding.operationId(h.parent, "enchant");
        JsonObject fresh = json("{\"snapshot_id\":\"" + UUID.randomUUID() + "\"}");
        check(h.task.persistAnswerParameters(answer(fresh)) == null && h.changed.get() == 1, "新观察先成为当前持久步骤");
        check(NativeSubmissionBinding.operationId(h.parent, "enchant").equals(original), "只换snapshot_id不能换消费编号");
        JsonObject budget = current(h).parameters(); budget.getAsJsonObject("production").getAsJsonObject("parameters").addProperty("max_lapis", 2);
        h.task.persistAnswerParameters(answer(budget));
        check(!NativeSubmissionBinding.operationId(h.parent, "enchant").equals(original)
                && current(h).parameters().getAsJsonObject("production").getAsJsonObject("parameters").get("max_lapis").getAsInt() == 2,
                "真实预算调整必须写回目标并进入消费身份");
        // 旧附魔前置失败后允许改用另一机制；崩溃恢复必须保留实际执行的工序，不能回到旧namespace重新消费。
        JsonObject changed = json("{\"production\":{\"schema_version\":2,\"process\":\"ae2:transform\",\"offset\":[1,0,0],"
                + "\"parameters\":{\"recipe_id\":\"example:observed_transform\",\"batches\":2}}}");
        check(h.task.persistAnswerParameters(answer(changed)) == null, "真实工序参数可在消费前经允许的retry修改");
        check(current(h).target().equals(h.parent.goal().target()) && current(h).target().position() == null,
                "写回参数不得把原机器地标解析成坐标或改写最初请求");
        String namespace = namespace(current(h)); UUID operation = NativeSubmissionBinding.operationId(h.parent, namespace);
        check(namespace.equals("world-process"), "消费命名空间来自写回后的实际工序");
        var journal = new TestJournal(h.identity, operation, namespace, h.queue); var consumer = new ConsumerRecord(namespace);
        consumer.submissionBarrier(NativeSubmissionBinding.barrier(h.parent, h.runtime, namespace, journal::prepare));
        check(!consumer.prepareSubmission(), "先等待包含新工序的父检查点"); h.queue.runNext();
        IntentTaskRecord restored = h.restore();
        check(current(h).toJson().equals(restored.steps().get(restored.stepIndex()).toJson())
                && namespace(restored.steps().get(restored.stepIndex())).equals(namespace), "磁盘恢复的实际目标和namespace必须与消费任务一致");
        check(!consumer.prepareSubmission(), "父目标落盘后再排预约写入"); h.queue.runNext();
        check(consumer.prepareSubmission(), "两份真实文件完成后才能消费");
        UUID recovered = NativeSubmissionBinding.operationId(restored, namespace);
        check(recovered.equals(operation), "恢复后必须落到实际工序同一个预约编号");
        var replay = new TestJournal(h.identity, recovered, namespace, h.queue);
        check(!replay.prepare(), "恢复实例仍检查已有预约"); h.queue.runNext();
        try { replay.prepare(); throw new AssertionError("恢复不得再次放行已预约消费"); }
        catch (IllegalStateException expected) { check(expected.getMessage().contains("already_reserved"), "必须因同一预约拒绝重发"); }
    }

    private static void forbiddenRetryPreservesGoal(Path directory) throws Exception {
        for (String flag : List.of("outcome_uncertain", "mechanical_retry_allowed")) {
            var h = new Harness(directory.resolve(flag), machine(false)); Goal before = current(h);
            var failure = TaskResult.fail("fixture prior consumption", Map.of(flag, flag.equals("outcome_uncertain")));
            h.parent.addAttempt(new IntentTaskRecord.AttemptSnapshot(0, before, TaskState.FAILED, failure.message(), failure.toJson(), 1));
            h.changed.set(0);
            // 连无效的新参数也不能先解析或写回；必须先保留旧失败禁令，避免改Goal让失败记录失配。
            var refusal = h.task.persistAnswerParameters(answer(json("{\"production\":{\"invalid\":true}}")));
            check(refusal != null && refusal.options().stream().noneMatch(option -> option.choice().equals("retry"))
                    && current(h).equals(before) && h.changed.get() == 0, "禁止重试时不改Goal、不创建新意图也不丢旧失败");
        }
    }

    private static void semanticTargetAndContinuation(Path directory) throws Exception {
        Goal travel = new Goal("maicraft:travel", "回到前面找到的营地",
                new Goal.SemanticTarget("prior_result", null, null, "前面找到的营地"), "{}", "{}", List.of(), List.of())
                .withInheritedProtection(List.of("保留的农田"));
        var h = new Harness(directory.resolve("target"), travel);
        check(h.task.persistAnswerParameters(answer(json("{\"exact\":true}"))) == null && current(h).target().equals(travel.target())
                && current(h).inheritedProtectionLabels().equals(travel.inheritedProtectionLabels()), "移动重试保留prior_result关系与继承保护");
        Goal mechanical = new Goal("maicraft:modify_machine", "连接原动力接口", new Goal.SemanticTarget("landmark", "目标机器", null, null),
                "{\"operation\":\"connect_mechanical_power\",\"snapshot_id\":\"" + UUID.randomUUID()
                        + "\",\"source_label\":\"原动力源\",\"allow_modify\":true}", "{}", List.of(), List.of());
        var m = new Harness(directory.resolve("continuation"), mechanical);
        UUID token = UUID.randomUUID(); Map<String, UUID> continuations = continuations(m.task);
        continuations.put(mechanical.toJson().toString(), token);
        m.task.persistAnswerParameters(answer(json("{\"snapshot_id\":\"" + UUID.randomUUID() + "\"}")));
        check(continuations.size() == 1 && token.equals(continuations.get(current(m).toJson().toString())), "只刷新观察保留同一机械施工续接凭据");
        m.task.persistAnswerParameters(answer(json("{\"source_label\":\"另一动力源\"}")));
        check(continuations.isEmpty(), "真实接口参数改变后旧机械路线不能授权新方案");
    }

    private static Goal machine(boolean build) {
        JsonObject parameters = json("{\"snapshot_id\":\"" + UUID.randomUUID() + "\",\"allow_use\":true,\"material_policy\":\"inventory_only\","
                + "\"production\":{\"schema_version\":2,\"process\":\"minecraft:enchanting\",\"parameters\":{\"item_id\":\"minecraft:book\",\"max_levels_spent\":1,\"max_lapis\":1}}}");
        if (build) {
            parameters.addProperty("allow_modify", true);
            parameters.add("blueprint", json("{\"schema_version\":1,\"blocks\":[{\"offset\":[0,0,0],\"block_id\":\"minecraft:enchanting_table\"}]}"));
        } else parameters.addProperty("operation", "run_production");
        return new Goal(build ? "maicraft:build_machine" : "maicraft:operate_machine", "在原场地完成加工",
                new Goal.SemanticTarget("landmark", "加工台", null, null), parameters.toString(), "{}", List.of(), List.of());
    }
    private static IntentTaskRecord.DecisionAnswer answer(JsonObject updates) {
        JsonObject details = new JsonObject(); details.add("parameters", updates);
        return new IntentTaskRecord.DecisionAnswer(UUID.randomUUID(), "retry", details.toString());
    }
    private static Goal current(Harness h) { return h.parent.steps().get(h.parent.stepIndex()); }
    private static String namespace(Goal goal) { return NativeProcessRegistry.consumptionNamespace(goal.parameters().getAsJsonObject("production").get("process").getAsString()); }
    @SuppressWarnings("unchecked") private static Map<String, UUID> continuations(IntentTask task) throws Exception {
        Field field = IntentTask.class.getDeclaredField("mechanicalContinuations"); field.setAccessible(true); return (Map<String, UUID>) field.get(task);
    }

    private static final class Harness {
        final ManualExecutor queue = new ManualExecutor(); final AtomicInteger changed = new AtomicInteger();
        final StateIdentity identity; final IntentStateStore store; final IntentRuntime runtime; final IntentTaskRecord parent; final IntentTask task;
        @SuppressWarnings("unchecked") Harness(Path directory, Goal goal) throws Exception {
            identity = new StateIdentity("d".repeat(64), directory);
            var storeConstructor = IntentStateStore.class.getDeclaredConstructor(Executor.class); storeConstructor.setAccessible(true); store = storeConstructor.newInstance(queue);
            var constructor = IntentRuntime.class.getDeclaredConstructor(); constructor.setAccessible(true); runtime = constructor.newInstance();
            field("stateStore").set(runtime, store); field("stateIdentity").set(runtime, identity); field("bodyAttached").set(runtime, true);
            parent = new IntentTaskRecord(UUID.randomUUID(), null, goal, identity.key()); parent.bindDirty(changed::incrementAndGet);
            ((Map<UUID, IntentTaskRecord>) field("tasks").get(runtime)).put(parent.externalId(), parent);
            ((Map<String, UUID>) field("requestKeys").get(runtime)).put("same-request", parent.externalId());
            task = new IntentTask(null, parent, runtime);
        }
        IntentTaskRecord restore() {
            // 独立Store读真实磁盘，排除当前运行时的内存Goal或旧pending_answer替测试掩盖恢复偏差。
            var loaded = new IntentStateStore().load(identity); check(loaded.status() == IntentStateStore.Status.LOADED, "父检查点必须可独立读取");
            var decoded = IntentStateCodec.decode(loaded.root()); var s = decoded.tasks().getFirst();
            check(decoded.requestKeys().get("same-request").equals(parent.externalId()), "修改参数不能改变父任务请求去重身份");
            return IntentTaskRecord.restored(s.id(), s.planId(), s.goal(), identity.key(), s.steps(), s.stepIndex(), s.completed(),
                    s.internalPositions(), s.internalAreaProtections(), s.attempts(), s.decision(), s.pendingAnswer(), s.terminal(), 100);
        }
    }
    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable action) { tasks.addLast(action); }
        void runNext() { tasks.removeFirst().run(); }
    }
    private static final class TestJournal extends NativeConsumptionJournal {
        TestJournal(StateIdentity identity, UUID operation, String namespace, Executor executor) { super(identity, operation, namespace, executor); }
    }
    private static final class ConsumerRecord extends NativeSubmissionTaskRecord {
        ConsumerRecord(String namespace) { super("retry-test", "retry-test", 1000, namespace); }
    }
    private static Field field(String name) throws Exception { Field field = IntentRuntime.class.getDeclaredField(name); field.setAccessible(true); return field; }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
