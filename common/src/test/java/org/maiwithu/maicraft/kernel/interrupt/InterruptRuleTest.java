// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Urgency;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 打断规则：立刻处理的打断一切；尽快处理的不打断停下不安全的动作；找空当处理的只在两个动作之间插进来。 */
class InterruptRuleTest {

    @Test
    void nowInterruptsEverything() {
        // 正在往虚空掉：不管手上在做什么都马上自救。
        for (Interruptibility task : Interruptibility.values()) {
            assertTrue(InterruptRule.canInterrupt(Urgency.NOW, task));
        }
    }

    @Test
    void soonWaitsOnlyWhenStoppingIsUnsafe() {
        // 被僵尸打：正常干活时还手，但悬空搭桥放这一格时先把方块放稳。
        assertTrue(InterruptRule.canInterrupt(Urgency.SOON, Interruptibility.BETWEEN_ACTIONS));
        assertTrue(InterruptRule.canInterrupt(Urgency.SOON, Interruptibility.WORKING));
        assertFalse(InterruptRule.canInterrupt(Urgency.SOON, Interruptibility.UNSAFE_TO_STOP));
    }

    @Test
    void laterOnlyUsesGapsBetweenActions() {
        // 饿了：挖完这一下、到两个动作之间再吃。
        assertTrue(InterruptRule.canInterrupt(Urgency.LATER, Interruptibility.BETWEEN_ACTIONS));
        assertFalse(InterruptRule.canInterrupt(Urgency.LATER, Interruptibility.WORKING));
        assertFalse(InterruptRule.canInterrupt(Urgency.LATER, Interruptibility.UNSAFE_TO_STOP));
    }
}
