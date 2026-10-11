// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 近处走位：带里站住、太近就退、身后是墙往侧面挪、两边夹住往开口退、台阶带跳、躲爆炸疾跑到范围外站住。 */
class FootworkTest {

    /** 哪儿都走得通的平地。 */
    private static final Footwork.Ground OPEN = (dx, dz) -> Footwork.Footing.FLAT;

    /** 僵尸在原点：它够得着我 2.02，我稳稳够得着它 3.15。 */
    private static final Footwork.Band ZOMBIE_BAND = new Footwork.Band(0, 0, 2.02, 3.15);

    private static Footwork.Stance melee(Footwork.Hazard... others) {
        return new Footwork.Stance(ZOMBIE_BAND, List.of(others), false);
    }

    @Test
    void standsStillInsideTheBand() {
        assertTrue(Footwork.choose(2.6, 0, melee(), OPEN).standing());
    }

    @Test
    void stepsBackWhenTooCloseAndInWhenTooFar() {
        Footwork.Step back = Footwork.choose(1.5, 0, melee(), OPEN);
        assertTrue(back.dx() > 0, "离它太近往外退，实际 " + back);
        Footwork.Step in = Footwork.choose(4.5, 0, melee(), OPEN);
        assertTrue(in.dx() < 0, "离它太远往里凑，实际 " + in);
    }

    @Test
    void sidestepsWhenTheWallIsBehind() {
        // 身后（东边三个方向）是墙：不站着挨打，往侧面挪出它够得着的地方。
        Footwork.Ground wallBehind = (dx, dz) -> dx > 0.1 ? Footwork.Footing.BLOCKED : Footwork.Footing.FLAT;
        Footwork.Step step = Footwork.choose(1.5, 0, melee(), wallBehind);
        assertFalse(step.standing(), "身后是墙也要挪");
        assertTrue(Math.abs(step.dz()) > 0.5, "往南或往北侧移，实际 " + step);
    }

    @Test
    void squeezedBetweenTwoMovesTowardTheOpening() {
        // 打的那只在西边，另一只从东边贴上来：南北是开口，往开口挪，让两只排成一线。
        Footwork.Stance stance = new Footwork.Stance(new Footwork.Band(-2.6, 0, 2.02, 3.15),
                List.of(new Footwork.Hazard(1.5, 0, 2.37)), false);
        Footwork.Step step = Footwork.choose(0, 0, stance, OPEN);
        assertTrue(Math.abs(step.dz()) > 0.5, "往开口侧移，实际 " + step);
        assertTrue(step.dx() <= 0, "不往东边那只身上撞");
    }

    @Test
    void jumpsUpAStep() {
        Footwork.Ground stepEast = (dx, dz) -> dx > 0.9 ? Footwork.Footing.STEP_UP : Footwork.Footing.BLOCKED;
        Footwork.Step step = Footwork.choose(1.5, 0, melee(), stepEast);
        assertEquals(1.0, step.dx(), 1e-9);
        assertTrue(step.jump(), "一格高的台阶带跳");
    }

    @Test
    void evadingABlastSprintsAwayAndStopsOutsideItsReach() {
        // 点着引信的苦力怕在原点：躲到 7 格熄火距离外（爆炸 6 格，取大的）。
        Footwork.Stance evade = new Footwork.Stance(null, List.of(new Footwork.Hazard(0, 0, 7.5)), true);
        Footwork.Step run = Footwork.choose(2, 0, evade, OPEN);
        assertTrue(run.dx() > 0.9 && run.sprint(), "背离它疾跑，实际 " + run);
        assertTrue(Footwork.choose(8, 0, evade, OPEN).standing(), "出了范围就站住看着它");
        // 背离它的方向被挡：斜着跑出去，不站在原地等炸。
        Footwork.Ground blocked = (dx, dz) -> dx > 0.9 ? Footwork.Footing.BLOCKED : Footwork.Footing.FLAT;
        Footwork.Step around = Footwork.choose(2, 0, evade, blocked);
        assertFalse(around.standing());
        assertTrue(around.dx() > 0, "仍然是往远离它的方向，实际 " + around);
    }

    @Test
    void exposure() {
        assertTrue(Footwork.exposed(1.5, 0, melee()));
        assertFalse(Footwork.exposed(2.6, 0, melee()));
        assertTrue(Footwork.exposed(2.6, 0, melee(new Footwork.Hazard(3, 1, 2.0))));
    }
}
