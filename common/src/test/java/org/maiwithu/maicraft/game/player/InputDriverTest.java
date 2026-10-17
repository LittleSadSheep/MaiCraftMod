// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 在水中 Shift 用于下潜，在陆地上则用于慢速潜行；两种姿态下疾跑的取舍不同。 */
class InputDriverTest {

    @Test
    void descentPreservesSwimmingSprint() {
        assertTrue(InputDriver.permitsSprint(true, true, true), "水中下潜时保留疾跑游泳");
    }

    @Test
    void groundedSneakingMustNotSprint() {
        assertFalse(InputDriver.permitsSprint(true, true, false), "地面潜行不能疾跑");
    }

    @Test
    void ordinaryRunning() {
        assertTrue(InputDriver.permitsSprint(true, false, false), "普通奔跑允许疾跑");
    }

    @Test
    void sprintCanBeExplicitlyStopped() {
        assertFalse(InputDriver.permitsSprint(false, true, true), "请求方可以显式停止疾跑");
    }
}
