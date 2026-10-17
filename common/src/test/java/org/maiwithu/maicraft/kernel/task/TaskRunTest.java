// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.TaskResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 一次任务运行只能往前走：PENDING → RUNNING → FINISHED，结束后不可再改。 */
class TaskRunTest {
    private record Sleep(String bed) implements TaskInput {
        @Override public String describe() {
            return "去" + bed + "睡觉";
        }
    }

    @Test
    void movesForwardOnly() {
        TaskRun run = new TaskRun(1, new Sleep("家里的床"));
        run.start(10);
        run.finish(TaskResult.done("躺下了"), 20);

        assertEquals(TaskState.FINISHED, run.state());
        assertEquals(10, run.startedTick());
        assertEquals(20, run.finishedTick());
        assertThrows(IllegalStateException.class, () -> run.finish(TaskResult.done("又躺了一次"), 30));
        assertThrows(IllegalStateException.class, () -> run.start(30));
    }

    @Test
    void canBeCancelledBeforeStarting() {
        TaskRun run = new TaskRun(2, new Sleep("村庄的床"));
        run.finish(TaskResult.cancelled("还没开始就被取消"), 5);

        assertEquals(TaskState.FINISHED, run.state());
        assertEquals(-1, run.startedTick());
    }
}
