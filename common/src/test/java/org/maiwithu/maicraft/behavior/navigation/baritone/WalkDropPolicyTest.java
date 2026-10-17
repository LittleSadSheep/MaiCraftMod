// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import baritone.api.pathing.movement.ActionCosts;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.FallDamage;

/** 走路往下跳：摔完还剩一半以上的血就跳、扣的血折成路程代价，不够就绕；卡坑判断认同一个高度。 */
class WalkDropPolicyTest {

    private static FallDamage body(float health) {
        return new FallDamage(health, 0, 3, 1, 0, 0, 0, 0, 0.08, 0, false);
    }

    @Test
    void injuryIsChargedPerHealthUntilHalfIsGone() {
        FallDamage full = body(20);
        assertEquals(0, WalkDropPolicy.injuryCost(full, 20, 3, FallDamage.Landing.ORDINARY, false), "三格不扣血");
        assertEquals(2 * WalkDropPolicy.COST_PER_HEALTH,
                WalkDropPolicy.injuryCost(full, 20, 5, FallDamage.Landing.ORDINARY, false), 1e-9, "五格扣一颗心");
        assertEquals(10 * WalkDropPolicy.COST_PER_HEALTH,
                WalkDropPolicy.injuryCost(full, 20, 13, FallDamage.Landing.ORDINARY, false), 1e-9, "剩一半照跳");
        assertEquals(ActionCosts.COST_INF,
                WalkDropPolicy.injuryCost(full, 20, 14, FallDamage.Landing.ORDINARY, false), "剩不到一半不跳");
        // 血已经不到一半：会扣血的一跳都不走，三格以内照走。
        assertEquals(ActionCosts.COST_INF, WalkDropPolicy.injuryCost(body(9), 20, 4, FallDamage.Landing.ORDINARY, false));
        assertEquals(0, WalkDropPolicy.injuryCost(body(9), 20, 3, FallDamage.Landing.ORDINARY, false));
    }

    @Test
    void injuryCostsMoreThanTheFallSaves() {
        // 跳八格扣五点：折成约二十格平路，比八格的下落时间（十几刻）贵得多，附近能走下去就走下去。
        double cost = WalkDropPolicy.injuryCost(body(20), 20, 8, FallDamage.Landing.ORDINARY, false);
        assertEquals(20, cost / ActionCosts.WALK_ONE_BLOCK_COST, 0.01);
    }

    @Test
    void maxDropIsAtLeastThreeAndFollowsTheBody() {
        assertEquals(12, WalkDropPolicy.maxDrop(body(20), 20, 12));
        assertEquals(5, WalkDropPolicy.maxDrop(body(12), 20, 12));
        assertEquals(WalkDropPolicy.MIN_DROP, WalkDropPolicy.maxDrop(body(6), 20, 12));
    }
}
