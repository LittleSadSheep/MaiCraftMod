// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 分堆：用原版左右键从一堆里分出恰好几件放进空格，点得尽量少；每一下都合原版规则，
 * 空格里累计不超过要分的数，最后鼠标上是空的、原格剩下其余的。
 */
class StackSplitTest {

    private static StackSplit.State finalState(List<StackSplit.Step> steps) {
        return steps.getLast().after();
    }

    @Test
    void fortyOutOfSixtyFourEndsExactlyAndQuickly() {
        List<StackSplit.Step> steps = StackSplit.plan(64, 40, 64);
        assertEquals(new StackSplit.State(24, 0, 40), finalState(steps));
        // 一件件放要四十下；拿半堆、放下、再拿半堆、补零头、退回，十来下就够。
        assertTrue(steps.size() <= 12, "点了 " + steps.size() + " 下");
        for (StackSplit.Step step : steps) {
            assertTrue(step.after().deposited() <= 40);
            assertEquals(step.after(), StackSplit.apply(step.before(), step.side(), step.button(), 40, 64));
        }
    }

    @Test
    void smallAndOddSplitsWork() {
        assertEquals(new StackSplit.State(7, 0, 3), finalState(StackSplit.plan(10, 3, 10)));
        assertEquals(new StackSplit.State(1, 0, 63), finalState(StackSplit.plan(64, 63, 64)));
    }

    @Test
    void hugeModStacksTakeABoundedPath() {
        assertEquals(new StackSplit.State(50, 0, 150), finalState(StackSplit.plan(200, 150, 200)));
    }

    @Test
    void nonsenseAmountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> StackSplit.plan(10, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> StackSplit.plan(10, 11, 10));
    }
}
