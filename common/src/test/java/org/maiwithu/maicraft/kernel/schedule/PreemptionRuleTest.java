// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.schedule;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Urgency;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 仲裁矩阵：致命打断一切，有害打断空闲与忙碌，舒适只在空闲时插入。 */
class PreemptionRuleTest {

    @Test
    void lethalNeedsInterruptEverything() {
        // 正在往虚空掉：不管手上在做什么都立刻自救。
        for (Interruptibility task : Interruptibility.values()) {
            assertTrue(PreemptionRule.preempts(Urgency.LETHAL, task));
        }
    }

    @Test
    void harmfulNeedsWaitForDelicateWork() {
        // 被僵尸打：正常干活时还手，但悬空搭桥放这一格时先把方块放稳。
        assertTrue(PreemptionRule.preempts(Urgency.HARMFUL, Interruptibility.FREE));
        assertTrue(PreemptionRule.preempts(Urgency.HARMFUL, Interruptibility.BUSY));
        assertFalse(PreemptionRule.preempts(Urgency.HARMFUL, Interruptibility.DELICATE));
    }

    @Test
    void comfortNeedsOnlyUseBreaks() {
        // 饿了：等挖完这一下、到阶段之间再吃。
        assertTrue(PreemptionRule.preempts(Urgency.COMFORT, Interruptibility.FREE));
        assertFalse(PreemptionRule.preempts(Urgency.COMFORT, Interruptibility.BUSY));
        assertFalse(PreemptionRule.preempts(Urgency.COMFORT, Interruptibility.DELICATE));
    }
}
