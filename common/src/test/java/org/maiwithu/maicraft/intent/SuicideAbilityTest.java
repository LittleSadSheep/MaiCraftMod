// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.core.task.suicide.SuicideTaskTest;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskSelector;
import org.maiwithu.maicraft.task.TaskState;

/** 通过真实语义父任务核对发现、暂停、自保恢复与死亡步骤保存，避免只测执行器却漏掉宿主重放。 */
public final class SuicideAbilityTest {
    public static void main(String[] args) throws Exception {
        var runtime = IntentRuntime.get();
        check(IntentRuntime.KNOWN_ABILITIES.contains("maicraft:suicide"), "主动寻死必须可发现");
        check(SemanticAbilityCatalog.parameterNames("maicraft:suicide").equals(Set.of(
                "method", "keep_inventory_confirmed", "search_radius", "timeout_seconds")), "发现参数须与执行器一致");
        for (String invalid : List.of("{\"method\":\"command\"}", "{\"keep_inventory_confirmed\":1}",
                "{\"search_radius\":100}", "{\"timeout_seconds\":0}", "{\"unknown\":true}")) {
            try { runtime.compile(goal(invalid), 0); throw new AssertionError("无效寻死目标不应被接单"); }
            catch (IllegalArgumentException expected) { }
        }
        Method respawn = GameplayAttentionMonitor.class.getDeclaredMethod("suicideAutoRespawn", IntentTaskRecord.class);
        respawn.setAccessible(true);
        check((Boolean) respawn.invoke(null, record(goal("{}"))), "主动寻死默认请求原生重生");
        check(!(Boolean) respawn.invoke(null, record(goal("{\"auto_respawn\":false}"))), "明确禁止自动重生应保留死亡决定");
        try (var world = new InteractionWorldTestHarness()) {
            SuicideTaskTest.prepare(world);
            Goal suicide = goal("{\"method\":\"lava\",\"keep_inventory_confirmed\":true,\"search_radius\":4}");
            runtime.compile(suicide, 0);
            var record = record(suicide);
            var task = new IntentTask(world.player, record, runtime);
            Task reflex = reflex();
            check(TaskSelector.select(List.of(reflex), null, task, List.of(), world.player) == task,
                    "首刻的饥饿自救不能永久挡住已授权寻死");
            SuicideTaskTest.begin(task, world);
            record.pause(world.level.getGameTime(), "paused_by_mcp");
            check(!task.suppressesSurvivalReflexes()
                    && TaskSelector.select(List.of(reflex), null, task, List.of(), world.player) == reflex,
                    "宿主暂停后立即恢复自救顺序");
            task.stop(world.player, Task.StopReason.PREEMPTED); record.resume();
            check(task.suppressesSurvivalReflexes(), "恢复同一寻死步骤时重新获得临时豁免");
            world.player.setHealth(0);
            check(task.observeDeath(world.player) && record.getState() == TaskState.SUCCESS && record.stepIndex() == 1,
                    "死亡事件先于普通 tick 也应保存已完成步骤");
            var result = task.result(TaskState.SUCCESS);
            check(result.success() && result.toJson().contains("death_observed") && !task.suppressesSurvivalReflexes(),
                    "公开回执必须保留死亡事实且结束保护豁免");
            check(!task.observeDeath(world.player), "父任务也不能重复结算死亡");
        }
        try (var world = new InteractionWorldTestHarness()) {
            SuicideTaskTest.prepare(world);
            var sequence = new Goal("maicraft:sequence", "寻死后再等待", null, "{}", "{}", List.of(), List.of(
                    goal("{\"method\":\"lava\",\"keep_inventory_confirmed\":true,\"search_radius\":4}"),
                    new Goal("maicraft:wait_for_condition", "等待重生后的现场", null, "{\"after_s\":1}", "{}", List.of(), List.of())));
            var record = record(sequence); var task = new IntentTask(world.player, record, runtime);
            SuicideTaskTest.begin(task, world); world.player.setHealth(0);
            check(task.observeDeath(world.player) && record.stepIndex() == 1 && record.getState() == TaskState.RUNNING,
                    "序列只完成寻死步骤，不能把后面的工作也冒称完成");
            check(!task.suppressesSurvivalReflexes() && record.stepResults().getFirst().success(), "后继步骤不能继承寻死豁免");
            task.stop(world.player, Task.StopReason.BODY_GONE);
            check(record.stepIndex() == 1, "重生清理旧身体不能倒退到再次寻死");
        }
        System.out.println("SuicideAbilityTest: passed");
    }

    private static IntentTaskRecord record(Goal goal) {
        var record = new IntentTaskRecord(UUID.randomUUID(), null, goal); record.setState(TaskState.RUNNING); return record;
    }
    private static Goal goal(String parameters) {
        return new Goal("maicraft:suicide", "在死亡不掉落时返回重生点", null, parameters, "{}", List.of(), List.of());
    }
    private static Task reflex() {
        // 模拟随时触发的饥饿自救，验证仲裁器不会执行任何额外进食或逃生动作。
        return new Task() {
            @Override public TaskState tick(LocalPlayer player) { return TaskState.RUNNING; }
            @Override public void stop(LocalPlayer player, StopReason why) { }
            @Override public String name() { return "test_survival_reflex"; }
        };
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
