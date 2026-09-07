// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.supply;

import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** Matching blocks must never erase a failed cleanup receipt or advance a sealed machine's stage. */
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
        System.out.println("BuildBatchCompletionTest: passed");
    }
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
