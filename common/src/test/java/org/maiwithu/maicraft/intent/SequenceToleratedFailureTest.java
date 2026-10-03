// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * on_failure=continue 的编排语义：确认失败的步骤记入账本后兄弟步骤继续，
 * 但部分失败也算完全失败；缺省 stop 与超时取消仍一损俱损。
 */
public final class SequenceToleratedFailureTest {
    private static final Set<String> KNOWN =
            Set.of("maicraft:sequence", "maicraft:enchant", "maicraft:wait_for_condition");

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        contractChecks();
        // 附魔台不存在的假世界让第一步确定性失败，真实走 tick 循环验证接续与终态。
        try (var world = new InteractionWorldTestHarness()) {
            toleratedFailureContinuesAndReportsFailed(world);
            defaultStopStillBlocks(world);
        }
        System.out.println("SequenceToleratedFailureTest: passed");
    }

    private static void contractChecks() {
        // 合法：sequence 直接子级声明 continue。
        var sequence = sequence(toleratedEnchant(), waitStep());
        SemanticGoalContract.validate(sequence, KNOWN);
        // 顶层目标与嵌套 sequence 都不能声明：外层递归校验内层 sequence 时拒绝分组容忍。
        rejects(toleratedEnchant(), "on_failure_not_allowed", "顶层非子级目标不能声明 on_failure");
        var flaggedNested = new Goal("maicraft:sequence", "内层组合", null, "{}", "{}", List.of(),
                List.of(enchantStep(), waitStep()), List.of(), "continue");
        var outer = new Goal("maicraft:sequence", "外层组合", null, "{}", "{}", List.of(),
                List.of(flaggedNested, waitStep()));
        rejects(outer, "on_failure_nested_sequence", "嵌套 sequence 子级不能声明 on_failure");
        // 外层自身声明时先按位置拒绝：sequence 不是兄弟步骤，分组容忍语义不存在。
        var outerWithFlag = new Goal("maicraft:sequence", "更外层", null, "{}", "{}", List.of(),
                List.of(new Goal("maicraft:wait_for_condition", "准备结束", null,
                        "{\"after_s\":0}", "{}", List.of(), List.of())), List.of(), "continue");
        rejects(outerWithFlag, "on_failure_not_allowed", "sequence 自身不是兄弟步骤，不能声明 on_failure");
        rejects(new Goal("maicraft:enchant", "附魔武器", null, "{\"item_id\":\"minecraft:diamond_sword\"}",
                "{}", List.of(), List.of(), List.of(), "retry"), "invalid_on_failure", "词表首发只有 stop/continue");
        var misplaced = new Goal("maicraft:enchant", "附魔武器", null,
                "{\"item_id\":\"minecraft:diamond_sword\",\"on_failure\":\"continue\"}",
                "{}", List.of(), List.of());
        try {
            SemanticGoalContract.validate(misplaced, KNOWN);
            throw new AssertionError("on_failure 误放进 parameters 应被拒绝并指向 goal 层字段");
        } catch (SemanticContractException expected) {
            check(expected.violationCode().equals("unknown_parameter")
                    && expected.getMessage().contains("goal-level"), "误放 parameters 时提示 goal 层字段");
        }
        // JSON 往返保留 continue；缺省 stop 不写回，旧请求与旧检查点的字段形状不变。
        var decoded = Goal.fromJson(sequence.toJson());
        check(decoded.children().getFirst().toleratesFailure()
                && !decoded.children().get(1).toleratesFailure()
                && !decoded.children().get(1).toJson().has("on_failure"),
                "on_failure 往返保留声明，stop 不产生新字段");
    }

    /** 第一步确认失败被容忍 → 第二步执行 → 整体 FAILED，账本披露两步的处理结果。 */
    private static void toleratedFailureContinuesAndReportsFailed(InteractionWorldTestHarness world) throws Exception {
        var runtime = runtime();
        var sequence = sequence(toleratedEnchant(), waitStep());
        runtime.compile(sequence, 0);
        var record = new IntentTaskRecord(UUID.randomUUID(), null, sequence);
        record.setState(TaskState.RUNNING);
        var task = new IntentTask(world.player, record, runtime);
        check(task.tick(world.player) == TaskState.RUNNING && record.stepIndex() == 1,
                "被容忍的失败应记入账本并接续兄弟步骤");
        var toleratedStep = record.stepResults().getFirst();
        check(!toleratedStep.success() && !toleratedStep.skipped(), "容忍失败记为真实失败，不混入跳过");
        check(task.tick(world.player) == TaskState.FAILED,
                "存在事实失败时整体终态必须是 FAILED，部分失败也算完全失败");
        TaskResult result = task.result(TaskState.FAILED);
        check(result.data().get("tolerated_failure_count").equals(1)
                && Boolean.FALSE.equals(result.data().get("all_steps_succeeded")),
                "终态回执必须点名被容忍的失败数量");
        var completed = (List<?>) result.data().get("completed_effects");
        check(completed != null && completed.size() == 2, "失败账本应披露两步都已处理");
        check(Boolean.FALSE.equals(((Map<?, ?>) completed.getFirst()).get("success"))
                && Boolean.TRUE.equals(((Map<?, ?>) completed.get(1)).get("success")),
                "失败步与成功步在账本中各报各的事实");
        check(((List<?>) result.data().get("remaining_effects")).isEmpty(),
                "全部步骤处理完后不得再报 pending 步骤");
        check(result.message().contains("on_failure=continue"),
                "失败说明要写明容忍来自编排声明");
    }

    /** 对照组：缺省 stop 仍旧第一步失败即终态，兄弟步骤保持未执行。 */
    private static void defaultStopStillBlocks(InteractionWorldTestHarness world) throws Exception {
        var runtime = runtime();
        var sequence = sequence(enchantStep(), waitStep());
        runtime.compile(sequence, 0);
        var record = new IntentTaskRecord(UUID.randomUUID(), null, sequence);
        record.setState(TaskState.RUNNING);
        var task = new IntentTask(world.player, record, runtime);
        check(task.tick(world.player) == TaskState.FAILED && record.stepIndex() == 0,
                "缺省 stop 一损俱损，清单停在失败步");
        check(record.stepResults().isEmpty(), "stop 路径不把失败步记成已处理步骤");
        TaskResult result = task.result(TaskState.FAILED);
        var remaining = (List<?>) result.data().get("remaining_effects");
        check(remaining != null && remaining.size() == 2
                && Map.class.cast(remaining.getFirst()).get("state").equals("failed_current")
                && Map.class.cast(remaining.get(1)).get("state").equals("pending"),
                "stop 路径披露当前失败步与未执行的兄弟步骤");
    }

    /** 附魔坐标不是附魔台：契约合法、适配期确定性确认失败，不需要真实世界状态。 */
    private static Goal toleratedEnchant() {
        return new Goal("maicraft:enchant", "附魔武器",
                new Goal.SemanticTarget("coordinates", null,
                        new Goal.WorldPosition(1, 64, 1, "minecraft:overworld"), null),
                "{\"item_id\":\"minecraft:diamond_sword\",\"max_levels_spent\":1,\"max_lapis\":1}",
                "{}", List.of(), List.of(), List.of(), "continue");
    }

    private static Goal enchantStep() {
        return new Goal("maicraft:enchant", "附魔武器",
                new Goal.SemanticTarget("coordinates", null,
                        new Goal.WorldPosition(1, 64, 1, "minecraft:overworld"), null),
                "{\"item_id\":\"minecraft:diamond_sword\",\"max_levels_spent\":1,\"max_lapis\":1}",
                "{}", List.of(), List.of());
    }

    private static Goal waitStep() {
        return new Goal("maicraft:wait_for_condition", "准备结束", null,
                "{\"after_s\":0}", "{}", List.of(), List.of());
    }

    private static Goal sequence(Goal first, Goal second) {
        return new Goal("maicraft:sequence", "附魔后完成准备", null, "{}", "{}", List.of(), List.of(first, second));
    }

    private static IntentRuntime runtime() throws Exception {
        var constructor = IntentRuntime.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void rejects(Goal goal, String code, String scene) {
        try {
            SemanticGoalContract.validate(goal, KNOWN);
            throw new AssertionError(scene + "：应被计划期校验拒绝");
        } catch (SemanticContractException expected) {
            check(expected.violationCode().equals(code), scene + "：错误码应为 " + code
                    + "，实际 " + expected.violationCode());
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
