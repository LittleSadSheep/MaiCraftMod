// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/** 走开方向挑选：背对丢出点起步，下一步要踩进刚丢的落点就换方向侧绕，绕不开就如实硬走。 */
class ClientStepsAsideTest {

    @Test
    void walksStraightBackWhenTheWayIsClear() {
        // 角色在丢出点后方一格：背对方向是 -Z，直走不踩落点。
        Vec3 direction = ClientStepsAside.steppingDirection(0, -1, candidate -> false);
        assertEquals(0.0, direction.x, 1.0e-6);
        assertEquals(-1.0, direction.z, 1.0e-6);
    }

    @Test
    void sidestepsWhenStraightBackStepsIntoTheLanding() {
        // 正后方就是刚丢的落点：往侧向绕，仍然不朝丢出点走。
        Vec3 direction = ClientStepsAside.steppingDirection(0, -1, candidate ->
                Math.abs(candidate.x) < 0.01 && candidate.z < 0);
        assertTrue(direction.z >= 0 || Math.abs(direction.x) > 0.01,
                "不该再朝正后方的落点走：" + direction);
    }

    @Test
    void fallsBackToStraightBackWhenEveryCandidateHitsTheLanding() {
        // 四面都是落点（被围住）：回原方向硬走，到期走不出去由期限如实报失败。
        Vec3 direction = ClientStepsAside.steppingDirection(0, -1, candidate -> true);
        assertEquals(0.0, direction.x, 1.0e-6);
        assertEquals(-1.0, direction.z, 1.0e-6);
    }

    @Test
    void noHorizontalOffsetPicksAFixedDirection() {
        // 角色还站在丢出点上没有水平位移：随便朝一个固定方向走，不除以零。
        Vec3 direction = ClientStepsAside.steppingDirection(0, 0, candidate -> false);
        assertEquals(1.0, direction.length(), 1.0e-6);
    }
}
