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
        verify(new Goal("maicraft:cook", "旧版烹饪数量", null,
                "{\"item_id\":\"minecraft:iron_ingot\",\"count\":1.5}", "{}", List.of(), List.of()));
        verify(new Goal("maicraft:cook", "旧版烹饪地点",
                new Goal.SemanticTarget("prior_result", null, null, null),
                "{\"item_id\":\"minecraft:iron_ingot\"}", "{}", List.of(), List.of()));
        verifySequenceSteps();
        verifyAdminChatHistory();
        verifyUnknownAbilityNamesOffender();
        verifyBlockedRecoveryExits();
        System.out.println("GoalCheckpointCompatibilityTest: passed");
    }

    private static void verifySequenceSteps() throws Exception {
        var constructor=IntentRuntime.class.getDeclaredConstructor();constructor.setAccessible(true);
        var runtime=constructor.newInstance();
        var identity=new StateIdentity("8".repeat(64),Files.createTempDirectory("sequence-goal-"));
        field("stateIdentity").set(runtime,identity);
        var store=(IntentStateStore)field("stateStore").get(runtime);
        var step=new Goal("maicraft:wait_for_condition","分析失败后仍继续收车",null,"{\"after_s\":1}","{}",
                List.of(),List.of(),List.of(),"continue");
        var goal=new Goal("maicraft:sequence","保留试运行后的收车步骤",null,"{}","{}",List.of(),List.of(step));
        SemanticGoalContract.validate(goal,IntentRuntime.KNOWN_ABILITIES);
        var plan=Plan.compile(goal,0);
        var original=new IntentTaskRecord(UUID.randomUUID(),plan.id(),goal,identity.key());
        original.addAttempt(new IntentTaskRecord.AttemptSnapshot(0,step,TaskState.FAILED,"fixture analysis failed",TaskResult.fail("analysis failed").toJson(),1));
        store.saveAsync(identity,IntentStateCodec.encode(identity.key(),List.of(plan),List.of(original),
                Map.of("sequence-goal",original.externalId()),List.of())).join();
        // 真实恢复入口必须同时恢复摊平步骤和失败尝试，不能删掉 continue 声明或清空整个存档绕过异常。
        var restore=IntentRuntime.class.getDeclaredMethod("restoreBound",long.class,String.class);restore.setAccessible(true);restore.invoke(runtime,100L,"body_reattached");
        runtime.requireRecoveredState();var restored=runtime.task(original.externalId());
        check(restored!=null&&restored.paused()&&restored.steps().getFirst().toleratesFailure(),"组合步骤的失败容忍声明未恢复");
        check(restored.pauseSnapshot()!=null
                &&restored.pauseSnapshot().reason().equals("paused_restored:body_reattached"),
                "恢复暂停应携带重载来源，供调用方区分死亡重生与普通重启");
        check(restored.attempts().getFirst().goal().toleratesFailure(),"历史失败尝试丢失原步骤语义");
        check(restored.goal().equals(goal)&&runtime.taskForRequestKey("sequence-goal")==restored,"重启后丢失组合目标或请求身份");
        try {runtime.compile(step,101);throw new AssertionError("恢复兼容不能放宽新请求的顶层 on_failure 规则");}
        catch(SemanticContractException expected) {check(expected.violationCode().equals("on_failure_not_allowed"),"错误的独立请求应仍被拒绝");}
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
        var restore = IntentRuntime.class.getDeclaredMethod("restoreBound", long.class, String.class);
        restore.setAccessible(true);
        restore.invoke(runtime, 100L, "session_start");
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

    /**
     * 旧检查点里的管理员命令 chat 任务（历史上合法注入，后来 559bbea5 把命令门禁默认关闭）：
     * 恢复校验不得拿当前政策一票否决整份历史；同一目标的新提交仍被命令政策拒绝，普通提交照常可用。
     */
    private static void verifyAdminChatHistory() throws Exception {
        var runtime = newRuntime("6".repeat(64), "admin-chat-");
        var identity = identity(runtime);
        var store = (IntentStateStore) field("stateStore").get(runtime);
        var goal = new Goal("maicraft:chat", "旧批次测试注入", null,
                "{\"text\":\"/tp @s 100 64 100\"}", "{}", List.of(), List.of());
        var plan = Plan.compile(goal, 0);
        var original = new IntentTaskRecord(UUID.randomUUID(), plan.id(), goal, identity.key());
        store.saveAsync(identity, IntentStateCodec.encode(identity.key(), List.of(plan), List.of(original),
                Map.of(), List.of())).join();
        invokeRestore(runtime, 100L, "session_start");
        runtime.requireRecoveredState();
        var restored = runtime.task(original.externalId());
        check(restored != null && restored.paused() && restored.restoredDetached(),
                "管理员命令 chat 历史应以暂停状态在场");
        check(restored.goal().parameters().equals(goal.parameters()), "旧 chat 请求内容原样保留");
        try {
            runtime.compile(goal, 101);
            throw new AssertionError("同一命令目标的新提交仍须被命令政策拒绝");
        } catch (SemanticContractException expected) {
            check("chat_command_not_allowed".equals(expected.violationCode()),
                    "新提交拒绝码应为 chat_command_not_allowed");
        }
        runtime.compile(new Goal("maicraft:chat", "普通问候", null,
                "{\"text\":\"hello\"}", "{}", List.of(), List.of()), 102);
    }

    /** 恢复失败（此处以旧版本能力名为例）必须点名肇事记录：日志与 state_restored 事件都携带 id 与原因。 */
    private static void verifyUnknownAbilityNamesOffender() throws Exception {
        var runtime = newRuntime("5".repeat(64), "offender-id-");
        var identity = identity(runtime);
        var store = (IntentStateStore) field("stateStore").get(runtime);
        var plan = Plan.compile(new Goal("maicraft:vanished_ability", "旧版本能力", null,
                "{}", "{}", List.of(), List.of()), 0);
        UUID offenderId = plan.id();
        store.saveAsync(identity, IntentStateCodec.encode(identity.key(), List.of(plan), List.of(),
                Map.of(), List.of())).join();
        invokeRestore(runtime, 100L, "session_start");
        try {
            runtime.requireRecoveredState();
            throw new AssertionError("未知能力的历史应阻断恢复并保留检查点");
        } catch (IllegalStateException expected) { }
        boolean named = false;
        for (var element : runtime.attention(0, 64).getAsJsonArray("events")) {
            var event = element.getAsJsonObject();
            if (!"state_restored".equals(event.get("type").getAsString())) continue;
            var data = event.getAsJsonObject("data");
            named = "recovery_blocked".equals(data.get("status").getAsString())
                    && data.get("recovery_failure").getAsString().contains("unknown_ability")
                    && data.has("offending_record_id")
                    && offenderId.toString().equals(data.get("offending_record_id").getAsString());
        }
        check(named, "state_restored 事件应携带失败原因与肇事计划编号");
    }

    /** 恢复受阻期间的取消与重生交接必须可达：取消是结算记录，重生依赖已保留的旧检查点，都不推进身体。 */
    private static void verifyBlockedRecoveryExits() throws Exception {
        var runtime = newRuntime("4".repeat(64), "blocked-exit-");
        var identity = identity(runtime);
        var store = (IntentStateStore) field("stateStore").get(runtime);
        var goal = new Goal("maicraft:wait_for_condition", "旧版等待", null,
                "{\"after_s\":1.5}", "{}", List.of(), List.of());
        var plan = Plan.compile(goal, 0);
        var original = new IntentTaskRecord(UUID.randomUUID(), plan.id(), goal, identity.key());
        store.saveAsync(identity, IntentStateCodec.encode(identity.key(), List.of(plan), List.of(original),
                Map.of(), List.of())).join();
        invokeRestore(runtime, 100L, "session_start");
        runtime.requireRecoveredState();
        var restored = runtime.task(original.externalId());
        // 模拟恢复之后配置或版本再度收紧：恢复闸落下，但取消与重生出口不得被一并卡死。
        store.preserveUnrestored(identity);
        field("bodyAttached").set(runtime, true);
        runtime.cancelRestored(restored, 102);
        check(restored.getState() == TaskState.CANCELLED, "恢复受阻时旧任务仍可取消");
        check(runtime.prepareRespawnHandoff(), "恢复受阻时重生交接必须放行：旧检查点已原样保留");
    }

    private static IntentRuntime newRuntime(String identityKey, String directoryPrefix) throws Exception {
        var constructor = IntentRuntime.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var runtime = constructor.newInstance();
        field("stateIdentity").set(runtime,
                new StateIdentity(identityKey, Files.createTempDirectory(directoryPrefix)));
        return runtime;
    }

    private static StateIdentity identity(IntentRuntime runtime) throws Exception {
        return (StateIdentity) field("stateIdentity").get(runtime);
    }

    private static void invokeRestore(IntentRuntime runtime, long gameTime, String reloadCause) throws Exception {
        var restore = IntentRuntime.class.getDeclaredMethod("restoreBound", long.class, String.class);
        restore.setAccessible(true);
        restore.invoke(runtime, gameTime, reloadCause);
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
