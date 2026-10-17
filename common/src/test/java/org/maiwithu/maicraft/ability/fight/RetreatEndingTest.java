// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.kernel.result.Problem;

/** 撤离收场：血低、没吃的、饱食度不够自然回血三样都卡住才点破互锁；有一样还有路就照普通话术。 */
class RetreatEndingTest {

    private static CombatSenses.CombatProfile body(double health, int food) {
        return new CombatSenses.CombatProfile(health, 0, Optional.empty(), food);
    }

    @Test
    void lowHealthNoFoodAndHungryPointsOutTheDeadEnd() {
        Problem problem = RetreatEnding.escaped("血到拒战线", body(5, 0), OptionalInt.of(12));

        assertEquals(Problem.Kind.DANGER, problem.kind());
        assertTrue(problem.message().contains("没有吃了不伤身的食物") && problem.message().contains("饱食度 12"),
                problem.message());
        assertTrue(problem.suggestion().contains("obtain") && problem.suggestion().contains("suicide"),
                problem.suggestion());
        assertFalse(problem.suggestion().contains("睡"), "睡觉不回血，不写：" + problem.suggestion());
    }

    @Test
    void anyWayToHealKeepsTheUsualWords() {
        // 身上有吃的、饱食度够自然回血、读不到饱食度（创造模式）：都照普通话术，不插长文。
        for (Problem problem : new Problem[] {
                RetreatEnding.escaped("血到拒战线", body(5, 2), OptionalInt.of(12)),
                RetreatEnding.escaped("血到拒战线", body(5, 0), OptionalInt.of(18)),
                RetreatEnding.escaped("血到拒战线", body(5, 0), OptionalInt.empty()),
                RetreatEnding.escaped("被围", body(16, 0), OptionalInt.of(6))}) {
            assertEquals("吃点东西回血到拒战线以上，或换把武器再来", problem.suggestion(), problem.message());
            assertFalse(problem.message().contains("走不通"), problem.message());
        }
    }

    @Test
    void shelterAtNightAlsoSaysHowToHealAfterDawn() {
        Problem stuck = RetreatEnding.needsShelter(body(4, 0), OptionalInt.of(3));
        assertTrue(stuck.suggestion().startsWith("天亮后先 obtain"), stuck.suggestion());
        Problem usual = RetreatEnding.needsShelter(body(4, 3), OptionalInt.of(3));
        assertEquals("天亮后回血、换把武器再来", usual.suggestion());
    }
}
