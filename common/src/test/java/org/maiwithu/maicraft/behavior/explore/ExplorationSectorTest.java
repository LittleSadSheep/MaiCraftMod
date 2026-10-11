// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.Target;

/** 扇形：出发时按视角把八向词换算成罗盘方位并固定，之后转头不改搜索方向。 */
class ExplorationSectorTest {

    @Test
    void 相对方位按出发朝向换算成罗盘方位并固定() {
        // 面朝西（视角角 90）时，前方就是西。
        ExplorationSector forward = ExplorationSector.fix(Target.Toward.FORWARD, 90f, 90, 0, 128);
        assertEquals(270, forward.centerBearing(), 0.01);
        // 之后调转视角，扇形还是朝西：出发时固定，转头不改搜索方向。
        ExplorationSector afterTurning = ExplorationSector.fix(Target.Toward.FORWARD, 90f, 90, 0, 128);
        assertEquals(afterTurning.centerBearing(), forward.centerBearing(), 0.01);
    }

    @Test
    void 左右按面朝的方向折算() {
        // 面朝北（视角角 180，正南为 0…… 北是 0）时，左手边是西。
        ExplorationSector facingNorth = ExplorationSector.fix(Target.Toward.FORWARD, 180f, 90, 0, 128);
        assertEquals(0, facingNorth.centerBearing(), 0.01);
        ExplorationSector left = ExplorationSector.fix(Target.Toward.LEFT, 180f, 90, 0, 128);
        assertEquals(270, left.centerBearing(), 0.01);
        ExplorationSector right = ExplorationSector.fix(Target.Toward.RIGHT, 180f, 90, 0, 128);
        assertEquals(90, right.centerBearing(), 0.01);
    }

    @Test
    void 世界方位不随朝向变() {
        assertEquals(0, ExplorationSector.fix(Target.Toward.NORTH, 90f, 90, 0, 128).centerBearing(), 0.01);
        assertEquals(90, ExplorationSector.fix(Target.Toward.EAST, 180f, 90, 0, 128).centerBearing(), 0.01);
        assertEquals(180, ExplorationSector.fix(Target.Toward.SOUTH, 0f, 90, 0, 128).centerBearing(), 0.01);
        assertEquals(270, ExplorationSector.fix(Target.Toward.WEST, 270f, 90, 0, 128).centerBearing(), 0.01);
    }

    @Test
    void 扇形内的点才算_扇形外只记不追() {
        // 朝北开口 90°：北与东北在扇形里，东不在。
        ExplorationSector sector = ExplorationSector.fix(Target.Toward.NORTH, 0f, 90, 16, 128);
        assertTrue(sector.contains(0, -100));
        assertTrue(sector.contains(70, -70));
        assertFalse(sector.contains(100, 0), "正东在开口之外");
        assertFalse(sector.contains(0, -10), "起找距离之内不算候选");
        assertFalse(sector.contains(0, -200), "半径之外不算候选");
    }

    @Test
    void 全向只看距离() {
        ExplorationSector sector = ExplorationSector.allAround(0, 64);
        assertTrue(sector.contains(-30, 40));
        assertTrue(sector.contains(0, -60));
        assertFalse(sector.contains(0, -100));
    }

    @Test
    void 跨圈的方位差也进扇形() {
        // 中线在 350°、开口 30°：0°（在带内）与 5°（跨过 360 的那一侧）都在扇形里。
        ExplorationSector sector = new ExplorationSector(350, 30, 0, 128);
        assertTrue(sector.contains(0, -100));
        assertTrue(sector.contains(-8, -99));
        assertFalse(sector.contains(30, -99), "偏了 17°，开口只有 30°");
        assertFalse(sector.contains(0, 100));
    }

    @Test
    void 轮换是顺时针转一个四十五度() {
        ExplorationSector rotated = ExplorationSector.fix(Target.Toward.NORTH, 0f, 90, 16, 128)
                .rotatedClockwise();
        assertEquals(45, rotated.centerBearing(), 0.01);
        assertEquals(90, rotated.angleDegrees(), 0.01);
    }
}
