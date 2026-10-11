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

    @Test
    void headlineStopsAtTheFirstSemicolonOutsideBrackets() {
        // 结论句在前、展开用分号接在后面；括号里的分号是补充，不从那里截断。
        Problem nested = Problem.of(Problem.Kind.NEED_ITEM,
                "还要1个床，问过各来源都拿不到；记得的容器：没有；试过自己做（缺羊毛；附近没有羊）");
        assertEquals("还要1个床，问过各来源都拿不到", nested.headline());
        Problem bracketed = Problem.of(Problem.Kind.NEED_ITEM, "试过自己做（缺羊毛；附近没有羊），一份都做不了；展开");
        assertEquals("试过自己做（缺羊毛；附近没有羊），一份都做不了", bracketed.headline());
        assertEquals("现在是白天", Problem.of(Problem.Kind.WRONG_TIME, "现在是白天").headline());
    }
}
