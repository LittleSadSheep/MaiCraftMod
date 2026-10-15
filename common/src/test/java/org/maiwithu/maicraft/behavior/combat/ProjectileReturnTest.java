// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * 火球是不是冲着身子来的：正对着飞来的算，擦着两格外飞过、往外飞、停着的不算。
 */
class ProjectileReturnTest {

    private static final Vec3 BODY = new Vec3(0, 65, 0);

    @Test
    void straightAtTheBodyCounts() {
        assertTrue(ProjectileReturn.headingAt(new Vec3(12, 66, 0), new Vec3(-1.5, -0.1, 0), BODY));
    }

    @Test
    void passingWideDoesNotCount() {
        assertFalse(ProjectileReturn.headingAt(new Vec3(12, 66, 4), new Vec3(-1.5, 0, 0), BODY));
    }

    @Test
    void flyingAwayDoesNotCount() {
        // 打回去以后掉头往外飞：不再接。
        assertFalse(ProjectileReturn.headingAt(new Vec3(3, 65, 0), new Vec3(1.5, 0, 0), BODY));
    }

    @Test
    void notMovingDoesNotCount() {
        assertFalse(ProjectileReturn.headingAt(new Vec3(3, 65, 0), Vec3.ZERO, BODY));
    }
}
