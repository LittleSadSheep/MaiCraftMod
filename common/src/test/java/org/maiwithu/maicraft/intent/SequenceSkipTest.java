package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.mcp.MaiCraftRuntimeFacade;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 通过真实决策跳过没有地点的记忆目标，确认后续步骤可继续，但未完成的目标不会被报告为已做成。 */
public final class SequenceSkipTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var constructor = IntentRuntime.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var runtime = constructor.newInstance();
        var missingPlace = new Goal("maicraft:remember_place", "记住营地", null,
                "{\"label\":\"camp\"}", "{}", List.of(), List.of());
        var wait = new Goal("maicraft:wait_for_condition", "准备结束", null,
                "{\"after_s\":0}", "{}", List.of(), List.of());
        var sequence = new Goal("maicraft:sequence", "记住营地并完成准备", null,
                "{}", "{}", List.of(), List.of(missingPlace, wait));
        runtime.compile(sequence, 0);
        var record = new IntentTaskRecord(UUID.randomUUID(), null, sequence);
        // 查看步骤的人不能偷偷删掉任务，从而绕过保护范围继承和进度保存通知。
        try {
            record.steps().clear();
            throw new AssertionError("步骤查询暴露了可直接修改的任务清单");
        } catch (UnsupportedOperationException expected) { }
        record.setState(TaskState.RUNNING);
        try (var world = new InteractionWorldTestHarness()) {
            var task = new IntentTask(world.player, record, runtime);
            check(task.tick(world.player) == TaskState.RUNNING && record.decisionSnapshot() != null,
                    "没有地点时应询问，不能凭空记住营地");
            var decision = record.decisionSnapshot();
            runtime.validateDecisionAnswer(record, "skip", new JsonObject());
            check(record.answer(decision.id(), "skip", new JsonObject()), "应接受明确跳过这一步");
            world.nextTick();
            check(task.tick(world.player) == TaskState.RUNNING && record.stepIndex() == 1,
                    "跳过后应继续后续步骤");
            check(!record.stepResults().getFirst().success(), "跳过的地点目标不能被记成成功");
            world.nextTick();
            check(task.tick(world.player) == TaskState.SUCCESS, "剩余步骤仍可正常结束");
            TaskResult result = task.result(TaskState.SUCCESS);
            check(!result.message().equals(sequence.outcome()), "结果不能直接宣称原来的全部目标已达成");
            check(Boolean.FALSE.equals(result.data().get("all_steps_succeeded"))
                    && result.data().get("skipped_step_count").equals(1), "最终结果应明确报告跳过");
            check(runtime.landmarks().isEmpty(), "被跳过的营地不能出现在地点记忆里");
            check(world.blockUses() == 0 && world.itemUses() == 0, "跳过不能触发额外游戏操作");
        }
        assertSkipped(snapshot(record));
        var events = runtime.attention(0, 20).toString();
        check(events.contains("step_skipped") && events.contains("all_steps_succeeded"),
                "主要通知通道也应保留跳过事实");

        // 新旧检查点都保留跳过语义；旧版固定跳过文字不能在恢复后重新变成成功地点。
        JsonObject saved = IntentStateCodec.encode("skip-test", List.of(), List.of(record), Map.of(), List.of());
        assertSkipped(snapshot(restored(saved)));
        var oldTask = saved.getAsJsonArray("tasks").get(0).getAsJsonObject();
        var oldStep = oldTask.getAsJsonArray("completed_steps").get(0).getAsJsonObject();
        oldStep.remove("skipped");
        oldStep.addProperty("success", true);
        oldStep.add("result", JsonParser.parseString(TaskResult.ok("step skipped by explicit decision").toJson()));
        oldTask.getAsJsonObject("terminal").add("result", JsonParser.parseString(TaskResult.ok(sequence.outcome()).toJson()));
        var legacy = restored(saved);
        assertSkipped(snapshot(legacy));
        legacy.retainInternalStepPosition(0, new Goal.WorldPosition(1, 64, 1, "minecraft:overworld"));
        check(PriorResultResolver.resolve(legacy,
                new Goal.SemanticTarget("prior_result", null, null, "camp"), "minecraft:overworld") == null,
                "旧记录里的残留位置也不能授权后续引用一个被跳过的目标");
        System.out.println("SequenceSkipTest: passed");
    }

    private static IntentTaskRecord restored(JsonObject saved) {
        var value = IntentStateCodec.decode(saved).tasks().getFirst();
        return IntentTaskRecord.restored(value.id(), value.planId(), value.goal(), "skip-test", value.steps(),
                value.stepIndex(), value.completed(), value.internalPositions(), value.internalAreaProtections(),
                value.attempts(), value.decision(), value.pendingAnswer(), value.terminal(), 50);
    }

    private static JsonObject snapshot(IntentTaskRecord record) throws Exception {
        var projection = MaiCraftRuntimeFacade.class.getDeclaredMethod("taskSnapshot", IntentTaskRecord.class);
        projection.setAccessible(true);
        return (JsonObject) projection.invoke(null, record);
    }

    private static void assertSkipped(JsonObject snapshot) {
        var step = snapshot.getAsJsonArray("completed_steps").get(0).getAsJsonObject();
        check(step.get("skipped").getAsBoolean() && !step.get("success").getAsBoolean(), "查询应区分跳过与成功");
        check(snapshot.get("skipped_step_count").getAsInt() == 1
                && !snapshot.get("all_steps_succeeded").getAsBoolean(), "查询应说明原清单没有全部成功");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
