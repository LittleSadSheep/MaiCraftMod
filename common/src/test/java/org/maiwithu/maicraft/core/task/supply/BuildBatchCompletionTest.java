// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.supply;

import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 匹配方块不能覆盖清理失败回执，也不能推进已封装机器的施工阶段。 */
public final class BuildBatchCompletionTest {
    public static void main(String[] args) {
        var geometryOnly = TaskResult.fail("cleanup unreachable", Map.of("aggregate_verification", true,
                "remaining_scaffolds", List.of(Map.of("x", 1, "y", 2, "z", 3))));
        check(!SemanticBuildSupplyCompanionTask.batchCompleted(TaskState.FAILED, geometryOnly), "geometry swallowed failed cleanup");
        check(!SemanticBuildSupplyCompanionTask.batchCompleted(TaskState.SUCCESS,
                TaskResult.ok("nominal completion", geometryOnly.data())), "remaining support was ignored");
        check(!SemanticBuildSupplyCompanionTask.batchCompleted(TaskState.TIMEOUT, TaskResult.ok("late")), "timeout became completion");
        check(SemanticBuildSupplyCompanionTask.batchCompleted(TaskState.SUCCESS, TaskResult.ok("verified and cleaned")), "clean receipt was rejected");
        check(SemanticBuildSupplyCompanionTask.batchCompleted(TaskState.SUCCESS,
                TaskResult.ok("verified and cleaned", Map.of("remaining_scaffolds", List.of()))), "empty ledger was rejected");
        // 子任务确认了上移放置不代表整份图纸已建好，供料父任务也不能为了原格为空而再索取同一材料。
        var redirected=TaskResult.ok("原生放置已确认",Map.of("native_placement_completed",true,
                "native_placement_deviation",Map.of("position","4,2,4"),"construction_complete",false));
        check(SemanticBuildSupplyCompanionTask.nativePlacementDeviation(TaskState.SUCCESS,redirected),"偏移原生效果没有交回模型处理");
        check(!SemanticBuildSupplyCompanionTask.batchCompleted(TaskState.SUCCESS,redirected),"偏移放置被计作完整施工批次");
        System.out.println("BuildBatchCompletionTest: passed");
    }
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
