// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.task.Urgency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 溺水判断：头在水里才管；氧气不够游上去、只剩两泡或已在掉血是立刻，不到三分之一是尽快。 */
class DrowningDangerTest {

    @Test
    void headOutOfWaterIsNotDrowning() {
        // 只有脚泡在水里、头露在外面：不缺氧，哪怕氧气条还没补满。
        assertNull(DrowningDanger.assess(SurvivalFakes.calm().air(0).build()));
    }

    @Test
    void waterBreathingNeedsNoAir() {
        assertNull(DrowningDanger.assess(SurvivalFakes.calm().underwater(10).breathing().build()),
                "有水下呼吸时不缺氧");
    }

    @Test
    void plentyOfAirIsNotUrgent() {
        assertNull(DrowningDanger.assess(SurvivalFakes.calm().underwater(240).build()));
        assertNull(DrowningDanger.assess(SurvivalFakes.calm().underwater(300).build()));
    }

    @Test
    void airBelowOneThirdNeedsAGapSoon() {
        // 剩 90 刻（三泡），刚低于三分之一，浅水里游上去绰绰有余：两个动作之间该浮头了。
        assertEquals(Urgency.SOON, DrowningDanger.assess(SurvivalFakes.calm().underwater(90).build()));
    }

    @Test
    void criticalAirOrDrowningDamageMustBeHandledNow() {
        assertEquals(Urgency.NOW, DrowningDanger.assess(SurvivalFakes.calm().underwater(60).build()), "只剩两泡");
        assertEquals(Urgency.NOW, DrowningDanger.assess(SurvivalFakes.calm().underwater(0).build()), "已经在掉血");
    }

    @Test
    void notEnoughAirToSwimUpFromDeepWaterIsNow() {
        // 深水底下还剩 150 刻：看着有五泡，可游上去要 160 刻，再不走就来不及。
        assertEquals(Urgency.NOW,
                DrowningDanger.assess(SurvivalFakes.calm().underwater(150).surfaceNeeds(160).build()));
    }
}
