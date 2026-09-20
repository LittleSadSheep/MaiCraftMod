package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.maiwithu.maicraft.task.TaskResult;

/** 前序地点引用必须依赖完成证据，多个地点要能区分，失败步骤不能替后续动作提供坐标。 */
public final class PriorResultResolverTest {
    private static final Goal.WorldPosition CAMP = new Goal.WorldPosition(2, 64, 3, "minecraft:overworld");
    private static final Goal.WorldPosition DOCK = new Goal.WorldPosition(12, 63, 8, "minecraft:overworld");

    public static void main(String[] args) {
        // 内部确认的位置优先于旧版文字结果中可能同时保留的位置，且不能被当前维度覆盖。
        var one = record("camp");
        one.retainInternalStepPosition(0, CAMP);
        one.addStepResult(new IntentTaskRecord.StepSnapshot(0, "maicraft:remember_place", true, "found camp",
                "{\"data\":{\"position\":{\"x\":99,\"y\":99,\"z\":99}}}"));
        check(CAMP.equals(resolve(one, "camp")), "内部确认位置应优先于兼容字段");

        // 有营地和码头两个结果时要按关系匹配；泛指“之前那个地方”不足以擅自选最新地点。
        var two = record("camp", "dock");
        complete(two, "camp", CAMP, true);
        complete(two, "dock", DOCK, true);
        check(DOCK.equals(resolve(two, "dock")), "应选择关系明确指向的码头");
        check(resolve(two, "place earlier") == null, "含糊关系不能自动选最近位置");
        var ambiguous = record("camp", "camp");
        complete(ambiguous, "camp", CAMP, true);
        complete(ambiguous, "camp", DOCK, true);
        check(resolve(ambiguous, "camp") == null, "同名但位置不同的两个完成结果需要进一步区分");

        var failed = record("camp");
        complete(failed, "camp", CAMP, false);
        check(resolve(failed, "camp") == null, "失败记录不能授权后续任务使用其位置");

        // 兼容旧结果的连续坐标时按方块格向下取整；缺维度的旧记录沿用调用方提供的当前维度。
        var legacy = record("camp");
        legacy.addStepResult(new IntentTaskRecord.StepSnapshot(0, "maicraft:remember_place", true, "found camp",
                "{\"data\":{\"final_x\":2.9,\"final_y\":64.1,\"final_z\":-0.1}}"));
        check(new Goal.WorldPosition(2, 64, -1, "minecraft:the_end").equals(resolve(legacy, "camp")),
                "旧位置证据应按原规则换成方块坐标");
        System.out.println("PriorResultResolverTest: passed");
    }

    private static IntentTaskRecord record(String... labels) {
        var steps = new ArrayList<Goal>();
        for (String label : labels) {
            var parameters = new JsonObject();
            parameters.addProperty("label", label);
            steps.add(new Goal("maicraft:remember_place", "remember " + label, null,
                    parameters.toString(), "{}", List.of(), List.of()));
        }
        return new IntentTaskRecord(UUID.randomUUID(), null,
                new Goal("maicraft:sequence", "record places", null, "{}", "{}", List.of(), steps));
    }

    private static void complete(IntentTaskRecord record, String label, Goal.WorldPosition position, boolean success) {
        int step = record.stepIndex();
        record.retainInternalStepPosition(step, position);
        record.addStepResult(new IntentTaskRecord.StepSnapshot(step, "maicraft:remember_place", success,
                "found " + label, TaskResult.ok("found " + label).toJson()));
    }

    private static Goal.WorldPosition resolve(IntentTaskRecord record, String relation) {
        return PriorResultResolver.resolve(record,
                new Goal.SemanticTarget("prior_result", null, null, relation), "minecraft:the_end");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
