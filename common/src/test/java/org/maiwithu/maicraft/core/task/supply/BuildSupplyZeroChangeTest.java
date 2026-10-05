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
 * 施工回执以世界净变化为准：提交时目标已满足或整轮零格变更都不得报 success，
 * 也不能再出现「0 verified construction batches 却宣称每格已复核」的空转假成功。
 */
public final class BuildSupplyZeroChangeTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        zeroChangeFailsInsteadOfVacuousSuccess();
        realChangeSucceedsWithCellAccounting();
        System.out.println("BuildSupplyZeroChangeTest: passed");
    }

    /** 173 主症状：目标格提交时已满足，旧链路会报「0 批次、每格已复核」的 success，现在必须如实失败。 */
    private static void zeroChangeFailsInsteadOfVacuousSuccess() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            BlockPos target = new BlockPos(5, 1, 5);
            h.set(target, Blocks.OAK_PLANKS.defaultBlockState());
            h.position(new Vec3(4.5, 1, 4.5));
            var task = parent(h, target);
            task.start(h.player);
            TaskState state = drive(task, h);
            check(state == TaskState.FAILED, "目标已满足且零世界变更时不得报 success");
            var result = task.result(TaskState.FAILED);
            check(!result.success() && "construction_zero_world_change".equals(result.data().get("failure_code")),
                    "失败回执点名零世界变更，而不是沿用空转成功话术");
            check(!result.message().contains("0 verified construction batch(es)"),
                    "空转假成功的原话术不得再以 success 面目出现");
            check(Integer.valueOf(0).equals(result.data().get("placed_cells"))
                            && Integer.valueOf(0).equals(result.data().get("verified_construction_batches")),
                    "回执携带 placed_cells 与批次计数，零变更时均为 0");
            check(Boolean.FALSE.equals(result.data().get("mechanical_retry_allowed")),
                    "零变更是对账线索而非可机械重试的施工故障");
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
