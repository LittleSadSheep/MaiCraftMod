// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.outcome;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 统一回执的约束：失败必须写明卡点；完成与取消不带卡点；部分完成可以带卡点。 */
class OutcomeTest {

    @Test
    void failureMustNameTheBlocker() {
        assertThrows(IllegalArgumentException.class,
                () -> Outcome.builder(Outcome.Status.FAILED, "没睡成").build());
    }

    @Test
    void doneAndCancelledCarryNoBlocker() {
        Blocker night = Blocker.of(Blocker.Kind.TIME_WINDOW, "现在是白天", "约 5 分钟后天黑");
        assertThrows(IllegalArgumentException.class,
                () -> Outcome.builder(Outcome.Status.DONE, "躺下了").blocker(night).build());
        assertThrows(IllegalArgumentException.class,
                () -> Outcome.builder(Outcome.Status.CANCELLED, "被取消").blocker(night).build());
    }

    @Test
    void partialCanExplainWhatIsLeft() {
        // 白天被叫去睡：今晚的床已经备好，入睡要等天黑。
        Outcome outcome = Outcome.builder(Outcome.Status.PARTIAL, "床备好了，现在睡不了")
                .achieved(Effect.of(Effect.Kind.ITEM_GAINED, "minecraft:white_bed", 1))
                .remaining("入睡")
                .blocker(Blocker.of(Blocker.Kind.TIME_WINDOW, "现在是白天", "约 5 分钟后天黑"))
                .build();

        assertEquals(1, outcome.achieved().size());
        assertEquals(Blocker.Kind.TIME_WINDOW, outcome.blocker().kind());
        assertSame(Facts.NONE, outcome.facts());
    }
}
