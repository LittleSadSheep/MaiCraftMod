// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.supply;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.preview.PreviewSession.Decision;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 施工回执如实记账世界净变化：提交时目标已满足按目标达成报 success，但必须标明 already_satisfied、
 * placed_cells=0，话术不得冒称“这次建好了”；真实变更的 success 携带能与现场对账的格数。
 */
public final class BuildSupplyZeroChangeTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        alreadySatisfiedSucceedsWithZeroChange();
        realChangeSucceedsWithCellAccounting();
        System.out.println("BuildSupplyZeroChangeTest: passed");
    }

    /** 173：目标格提交时已满足——目标达成仍报 success，但回执与话术都写明这次一格没动，不冒称“建好了”。 */
    private static void alreadySatisfiedSucceedsWithZeroChange() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            BlockPos target = new BlockPos(5, 1, 5);
            h.set(target, Blocks.OAK_PLANKS.defaultBlockState());
            h.position(new Vec3(4.5, 1, 4.5));
            var task = parent(h, target);
            task.start(h.player);
            TaskState state = drive(task, h);
            check(state == TaskState.SUCCESS, "目标已满足就是目标达成，不能改判成失败让调用方重试");
            var result = task.result(TaskState.SUCCESS);
            check(result.success() && Boolean.TRUE.equals(result.data().get("already_satisfied")),
                    "成功回执标明开工前就已满足");
            check(result.message().contains("already matched the target before construction")
                            && !result.message().contains("verified construction batch(es)"),
                    "话术写明本来就满足、0 格变更，不沿用“批次已复核”的建成话术");
            check(Integer.valueOf(0).equals(result.data().get("placed_cells"))
                            && Integer.valueOf(0).equals(result.data().get("verified_construction_batches")),
                    "回执携带 placed_cells 与批次计数，零变更时均为 0");
        }
    }

    /** 真实变更场景：缺口被填掉后 success 才成立，且回执的格数与批次计数能和世界净变化对账。 */
    private static void realChangeSucceedsWithCellAccounting() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            BlockPos target = new BlockPos(5, 1, 5);
            h.set(target, Blocks.AIR.defaultBlockState());
            h.position(new Vec3(4.5, 1, 4.5));
            var task = parent(h, target);
            task.start(h.player);
            // 推进到父任务开出施工批次，再用确认成功的批次回执接住，不让夹具真的去点击世界。
            for (int i = 0; i < 40 && field(task, "activeChild").get(task) == null; i++) task.tick(h.player);
            check(Integer.valueOf(1).equals(field(task, "buildRounds").get(task)), "缺料已备齐的单格方案应直接开出施工批次");
            field(task, "activeChild").set(task, new Receipt(TaskState.SUCCESS, Map.of()));
            h.set(target, Blocks.OAK_PLANKS.defaultBlockState());
            TaskState state = drive(task, h);
            check(state == TaskState.SUCCESS, "世界净变化落实后按正常路径验收");
            var result = task.result(TaskState.SUCCESS);
            check(result.success() && Boolean.TRUE.equals(result.data().get("goal_satisfied")),
                    "有净变化的完成回执保持 goal_satisfied");
            check(Integer.valueOf(1).equals(result.data().get("placed_cells"))
                            && Integer.valueOf(1).equals(result.data().get("verified_construction_batches")),
                    "success 回执携带 1 格净变化与 1 个已核验批次，可与现场对账");
            check(result.message().contains("1 build cell(s) changed to target"),
                    "成功话术写明实际变更格数，不再只宣称每格已复核");
            check(Boolean.FALSE.equals(result.data().get("already_satisfied")), "真实施工不标记为本来就满足");
        }
    }

    private static SemanticBuildSupplyCompanionTask parent(InteractionWorldTestHarness h, BlockPos at) {
        var plan = new BuildTaskRecord("zero-change", 1000, List.of(new BuildTaskRecord.Target(
                Blocks.OAK_PLANKS, Items.OAK_PLANKS, at, "wall", null, null, null)), false);
        h.inventory.setItem(0, new ItemStack(Items.OAK_PLANKS));
        var record = new SemanticBuildSupplyTaskRecord("zero-change-supply", 1000, plan,
                SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY, List.of(), false, List.of(), false);
        return new SemanticBuildSupplyCompanionTask(h.player, record, (owner, frozen) -> Decision.DISABLED);
    }

    /** 推进父任务直到终态；批次子任务由夹具回执替代，这里只考核父任务的记账与判定。 */
    private static TaskState drive(SemanticBuildSupplyCompanionTask task, InteractionWorldTestHarness h) {
        for (int i = 0; i < 60; i++) {
            TaskState state = task.tick(h.player);
            if (state.isTerminal()) return state;
        }
        throw new AssertionError("parent task did not reach a terminal state");
    }

    private static final class Receipt implements Task {
        private final TaskState state; private final Map<String, Object> data;
        Receipt(TaskState state, Map<String, Object> data) { this.state = state; this.data = data; }
        public TaskState tick(LocalPlayer player) { return state; }
        public void stop(LocalPlayer player, StopReason reason) {}
        public String name() { return "模拟施工批次回执"; }
        public TaskResult result(TaskState terminal) { return new TaskResult(state == TaskState.SUCCESS, "模拟批次已结束", false, false, data); }
    }

    private static Field field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
