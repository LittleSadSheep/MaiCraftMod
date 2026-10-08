// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.drop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.DropAvoidance;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/** 丢弃落点避让：登记过的落点周围算避让格，过期与不同维度不算。 */
class DropAvoidanceTest {

    @Test
    void 登记过的落点周围算避让格() {
        DropAvoidance avoidance = new DropAvoidance();
        avoidance.register(WorldPosition.here(100, 64, 100), 1000);
        assertTrue(avoidance.shouldAvoid(WorldPosition.here(101, 64, 101), 1001));
        assertFalse(avoidance.shouldAvoid(WorldPosition.here(110, 64, 100), 1001));
    }

    @Test
    void 过期的落点不再避让() {
        DropAvoidance avoidance = new DropAvoidance();
        avoidance.register(WorldPosition.here(0, 64, 0), 1000);
        assertFalse(avoidance.shouldAvoid(WorldPosition.here(0, 64, 0), 1000 + DropAvoidance.KEEP_TICKS + 1));
    }
}
