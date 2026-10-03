// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * mine 子任务如实报采区耗尽（mined_out）后，父层终态必须保真为 MINED_OUT，
 * recovery_options 才会给出"换区域重扫"；压回 no_material 会让调用方误判成许可缺口（issue 004）。
 */
public final class AcquisitionMineExhaustionRecoveryTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        scenario("mined_out", true);
        scenario("no_material", false);
        System.out.println("AcquisitionMineExhaustionRecoveryTest: passed");
    }

    /** childFailureType 是模拟 mine 子任务回执里的 failure_type；expectRelocate 期望回执含换区域重扫选项。 */
    private static void scenario(String childFailureType, boolean expectRelocate) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var cobble = ResourceLocation.parse("minecraft:cobblestone");
            var record = new SemanticAcquireTaskRecord("mine-exhausted", 1000, List.of(cobble), 40,
                    List.of(SemanticAcquireTaskRecord.Source.MINE), false,
                    SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 8);
            var task = new SemanticAcquireCompanionTask(h.player, record);
            task.onStart();
            Object need = get(task, "rootNeed");
            var mineRecord = new MineBlockTaskRecord("mine-exhausted-internal", 900,
                    Set.of(Blocks.STONE), 40, "stone", Set.of(Items.COBBLESTONE));
            var start = task.getClass().getDeclaredMethod("startChild", need.getClass(),
                    SemanticAcquireTaskRecord.Source.class, TaskRecord.class, String.class);
            start.setAccessible(true);
            start.invoke(task, need, SemanticAcquireTaskRecord.Source.MINE, mineRecord, "mine stone");
            set(task, "activeChild", new StubMineChild(childFailureType));
            var tick = task.getClass().getDeclaredMethod("tickActiveChild");
            tick.setAccessible(true);
            check(tick.invoke(task) == TaskState.RUNNING, "mine 子任务失败后父层先换下一来源而非立刻终态");
            TaskState state = TaskState.RUNNING;
            for (int i = 0; i < 16 && state == TaskState.RUNNING; i++) state = task.onTick();
            check(state == TaskState.FAILED, "唯一来源耗尽后父任务必须终态失败");
            var data = task.result(TaskState.FAILED).data();
            check(childFailureType.equals(data.get("failure_type")),
                    "父层终态必须保真子任务失败类型，实际: " + data.get("failure_type"));
            check("allowed_sources_exhausted".equals(data.get("failure_code")),
                    "失败码保持来源耗尽口径，实际: " + data.get("failure_code"));
            var options = (List<?>) data.get("recovery_options");
            var ids = options.stream().map(option -> ((Map<?, ?>) option).get("id")).toList();
            check(ids.contains("continue_mining_from_another_semantic_area") == expectRelocate,
                    "换区域重扫选项出现与否必须跟随 mine 子任务的采区耗尽证据，实际: " + ids);
            check(h.blockUses() == 0 && h.itemUses() == 0, "恢复选项推演不产生任何世界副作用");
        }
    }

    /** 模拟 mine 子任务：直接以给定 failure_type 失败，不挖任何真实方块。 */
    private record StubMineChild(String failureType) implements Task {
        @Override public TaskState tick(LocalPlayer player) { return TaskState.FAILED; }
        @Override public void stop(LocalPlayer player, StopReason reason) {}
        @Override public String name() { return "模拟 mine 采区耗尽"; }
        @Override public TaskResult result(TaskState state) {
            return TaskResult.fail("gathered 19/40, no more stone in range",
                    Map.of("failure_type", failureType));
        }
    }

    private static Object get(Object instance, String name) throws Exception {
        var field = instance.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(instance);
    }

    private static void set(Object instance, String name, Object value) throws Exception {
        var field = instance.getClass().getDeclaredField(name); field.setAccessible(true); field.set(instance, value);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
