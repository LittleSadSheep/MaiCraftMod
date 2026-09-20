package org.maiwithu.maicraft.intent;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.task.TaskState;

/** 从目标校验到实际父任务推进等待，核对时间、身体条件和暂停，不给角色安排额外动作。 */
public final class WaitGoalTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        rejectsAmbiguousParameters();
        elapsedTimeContinuesWhilePaused();
        observesLiveConditions();
        sequenceAdvancesOneGoalAtATime();
        System.out.println("WaitGoalTest: passed");
    }

    private static IntentRuntime runtime() throws Exception {
        var constructor = IntentRuntime.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void rejectsAmbiguousParameters() throws Exception {
        var runtime = runtime();
        // 计划不能把错误时长悄悄改成别的数值，也不能接受执行时才发现不认识的条件。
        for (String parameters : List.of("{\"after_s\":1.5}", "{\"after_s\":-1}", "{\"after_s\":3601}",
                "{\"after_s\":2147483648}", "{\"after_s\":\"2\"}", "{\"after_s\":null}", "{\"after_s\":{}}",
                "{\"condition\":true}", "{\"condition\":null}", "{\"condition\":[]}", "{\"condition\":\"daytime\"}")) {
            try {
                runtime.compile(goal(parameters), 0);
                throw new AssertionError("等待计划接受了不明确的参数: " + parameters);
            } catch (SemanticContractException expected) {
                check(expected.getMessage().contains("after_s") || expected.getMessage().contains("condition"),
                        "应指出出错的等待字段");
            }
        }
        for (String parameters : List.of("{}", "{\"after_s\":0}", "{\"after_s\":3600}",
                "{\"condition\":\"elapsed\"}", "{\"condition\":\"day\"}", "{\"condition\":\"night\"}",
                "{\"condition\":\"health_full\"}", "{\"condition\":\"not_hungry\"}")) {
            check(runtime.compile(goal(parameters), 0).steps().size() == 1, "合法等待目标应保留为一步");
        }
    }

    private static void elapsedTimeContinuesWhilePaused() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var record = record(goal("{\"after_s\":2}"));
            var task = new IntentTask(world.player, record, runtime());
            check(task.tick(world.player) == TaskState.RUNNING, "两秒等待不能在接单时结束");
            for (int i = 0; i < 39; i++) world.nextTick();
            check(task.tick(world.player) == TaskState.RUNNING, "不足四十个游戏刻仍要等待");
            record.pause(world.level.getGameTime(), "paused_by_mcp");
            check(!task.canRun(world.player), "暂停时不参与身体调度");
            for (int i = 0; i < 20; i++) world.nextTick();
            record.resume();
            check(task.tick(world.player) == TaskState.SUCCESS, "继续任务后应承认世界里已经过去的时间");
            check(world.blockUses() == 0 && world.itemUses() == 0, "等待不应虚构身体操作");
        }
    }

    private static void observesLiveConditions() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            // 同一条件从不满足变成满足时，只读当前世界；不替玩家进食、治疗或改变时间。
            var time = new ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false);
            Field field = Level.class.getDeclaredField("levelData");
            field.setAccessible(true);
            field.set(world.level, time);
            for (String condition : List.of("day", "night", "health_full", "not_hungry")) {
                time.setDayTime(condition.equals("day") ? 13_000 : 12_999);
                world.player.setHealth(world.player.getMaxHealth() - 2);
                world.player.getFoodData().setFoodLevel(17);
                var task = new IntentTask(world.player, record(goal(
                        "{\"after_s\":0,\"condition\":\"" + condition + "\"}")), runtime());
                check(task.tick(world.player) == TaskState.RUNNING, "条件未满足时保持等待: " + condition);
                time.setDayTime(condition.equals("day") ? 0 : 13_000);
                world.player.setHealth(world.player.getMaxHealth());
                world.player.getFoodData().setFoodLevel(18);
                world.nextTick();
                check(task.tick(world.player) == TaskState.SUCCESS, "应识别最新条件: " + condition);
            }
            check(world.blockUses() == 0 && world.itemUses() == 0, "条件观察不能消耗或使用物品");
        }
    }

    private static void sequenceAdvancesOneGoalAtATime() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            // 即使连续两项条件都已满足，也各自记一份步骤结果，不能一次跳过整组目标。
            var instant = goal("{\"after_s\":0}");
            var sequence = new Goal("maicraft:sequence", "按顺序确认条件", null, "{}", "{}",
                    List.of(), List.of(instant, instant));
            var record = record(sequence);
            var task = new IntentTask(world.player, record, runtime());
            check(task.tick(world.player) == TaskState.RUNNING && record.stepIndex() == 1,
                    "第一次推进只完成第一步");
            world.nextTick();
            check(task.tick(world.player) == TaskState.SUCCESS && record.stepResults().size() == 2,
                    "第二步完成后才报告整个序列成功");
        }
    }

    private static IntentTaskRecord record(Goal goal) {
        var record = new IntentTaskRecord(UUID.randomUUID(), null, goal);
        record.setState(TaskState.RUNNING);
        return record;
    }

    private static Goal goal(String parameters) {
        return new Goal("maicraft:wait_for_condition", "等待当前条件", null,
                parameters, "{}", List.of(), List.of());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
