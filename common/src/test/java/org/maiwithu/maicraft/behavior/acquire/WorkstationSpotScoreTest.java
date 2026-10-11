// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 就地摆工作站挑格：眼前的胜过身后的，同一层的胜过脚下一层的，近的胜过远的。 */
class WorkstationSpotScoreTest {

    // 面朝南（+z）。
    private static double score(int dx, int dy, int dz) {
        return ClientWorkstationPlacer.spotScore(dx, dy, dz, 0, 1);
    }

    @Test
    void inFrontAndOnTheSameLevelWins() {
        assertTrue(score(0, 0, 1) < score(0, 0, -1), "眼前胜过身后");
        assertTrue(score(1, 0, 1) < score(0, -1, 1), "同一层胜过脚下一层");
        assertTrue(score(0, 0, 1) < score(0, 0, 2), "近的胜过远的");
    }
}
