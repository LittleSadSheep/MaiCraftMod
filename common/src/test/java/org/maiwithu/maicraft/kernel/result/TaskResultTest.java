// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.result;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 任务结果的约束：失败必须写明问题；完成与取消不带问题；部分完成可以带问题。 */
class TaskResultTest {

    @Test
    void failureMustNameTheProblem() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskResult.builder(TaskResult.Status.FAILED, "没睡成").build());
    }

    @Test
    void doneAndCancelledCarryNoProblem() {
        Problem daytime = Problem.of(Problem.Kind.WRONG_TIME, "现在是白天", "约 5 分钟后天黑");
        assertThrows(IllegalArgumentException.class,
                () -> TaskResult.builder(TaskResult.Status.DONE, "躺下了").problem(daytime).build());
        assertThrows(IllegalArgumentException.class,
                () -> TaskResult.builder(TaskResult.Status.CANCELLED, "被取消").problem(daytime).build());
    }

    @Test
    void partialCanExplainWhatIsLeft() {
        // 白天被叫去睡：今晚的床已经备好，入睡要等天黑。
        TaskResult result = TaskResult.builder(TaskResult.Status.PARTIAL, "床备好了，现在睡不了")
                .change(Change.of(Change.Kind.ITEM_GAINED, "minecraft:white_bed", 1))
                .remaining("入睡")
                .problem(Problem.of(Problem.Kind.WRONG_TIME, "现在是白天", "约 5 分钟后天黑"))
                .build();

        assertEquals(1, result.changes().size());
        assertEquals(Problem.Kind.WRONG_TIME, result.problem().kind());
        assertSame(ResultDetails.NONE, result.details());
    }
}
