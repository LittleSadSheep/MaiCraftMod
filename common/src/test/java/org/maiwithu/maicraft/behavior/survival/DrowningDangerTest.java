// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.task.Urgency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 溺水判断：氧气见底或已在掉血就立刻浮头；剩不到三分之一找空当换气；头没在水里不管。 */
class DrowningDangerTest {

    @Test
    void headOutOfWaterIsNotDrowning() {
        // 只有脚泡在水里、头露在外面：不缺氧，哪怕氧气条已经空了。
        assertNull(DrowningDanger.assess(situation(false, 0, false)));
    }

    @Test
    void plentyOfAirIsNotUrgent() {
        assertNull(DrowningDanger.assess(situation(true, 8, false)));
        assertNull(DrowningDanger.assess(situation(true, DrowningDanger.MAX_BUBBLES, false)));
    }

    @Test
    void airBelowOneThirdNeedsAGapSoon() {
        // 剩 3 泡，刚低于三分之一：不急，但两个动作之间该浮头了。
        assertEquals(Urgency.SOON, DrowningDanger.assess(situation(true, 3, false)));
    }

    @Test
    void criticalAirOrDrowningDamageMustBeHandledNow() {
        // 只剩两泡：再不换气就开始掉血。
        assertEquals(Urgency.NOW, DrowningDanger.assess(situation(true, 2, false)));
        assertEquals(Urgency.NOW, DrowningDanger.assess(situation(true, 0, false)));
        // 氧气还够但已经开始掉血：马上浮头。
        assertEquals(Urgency.NOW, DrowningDanger.assess(situation(true, 5, true)));
    }

    private DrowningDanger.View situation(boolean headInWater, int airBubbles, boolean drowning) {
        return new DrowningDanger.View() {
            @Override public boolean headInWater() {
                return headInWater;
            }

            @Override public int airBubbles() {
                return airBubbles;
            }

            @Override public boolean drowning() {
                return drowning;
            }
        };
    }
}
