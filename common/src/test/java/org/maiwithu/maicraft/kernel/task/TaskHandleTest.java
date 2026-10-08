// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.outcome.Outcome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 任务生命周期只能前进：PENDING → RUNNING → FINISHED，终态不可再改。 */
class TaskHandleTest {
    private record Sleep(String bed) implements TaskRecord {
        @Override public String describe() {
            return "去" + bed + "睡觉";
        }
    }

    @Test
    void movesForwardOnly() {
        TaskHandle handle = new TaskHandle(1, new Sleep("家里的床"));
        handle.start(10);
        handle.finish(Outcome.done("躺下了"), 20);

        assertEquals(TaskState.FINISHED, handle.state());
        assertEquals(10, handle.startedTick());
        assertEquals(20, handle.finishedTick());
        assertThrows(IllegalStateException.class, () -> handle.finish(Outcome.done("又躺了一次"), 30));
        assertThrows(IllegalStateException.class, () -> handle.start(30));
    }

    @Test
    void canBeCancelledBeforeStarting() {
        TaskHandle handle = new TaskHandle(2, new Sleep("村庄的床"));
        handle.finish(Outcome.cancelled("还没开始就被取消"), 5);

        assertEquals(TaskState.FINISHED, handle.state());
        assertEquals(-1, handle.startedTick());
    }
}
