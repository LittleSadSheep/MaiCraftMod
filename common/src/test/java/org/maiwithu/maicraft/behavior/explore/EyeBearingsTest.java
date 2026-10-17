// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 末影之眼指路的判断：往下钻、两次飞向交叉、走过头掉头、离得远时放长一段。 */
class EyeBearingsTest {

    // 在 (x, z) 出手，眼横飞到 (x+dx, z+dz) 并升了 8 格后落下：离要塞还远时原版就是这样飞的。
    private static EyeFlight flew(double x, double z, double dx, double dz) {
        return new EyeFlight(x, 70, z, x + dx, 78, z + dz, EyeFlight.Landing.DROPPED, 5);
    }

    // 朝着 (towardX, towardZ) 横飞 12 格：按真要塞的位置算眼的方向。
    private static EyeFlight toward(double x, double z, double towardX, double towardZ) {
        double length = Math.hypot(towardX - x, towardZ - z);
        return flew(x, z, (towardX - x) / length * 12, (towardZ - z) / length * 12);
    }

    @Test
    void 眼往下钻就是要塞在脚下() {
        EyeFlight dive = new EyeFlight(100, 70, 100, 104, 41, 97, EyeFlight.Landing.SHATTERED, -1);
        EyeBearings.Verdict verdict = EyeBearings.judge(null, dive, false);

        EyeBearings.Verdict.Found found = assertInstanceOf(EyeBearings.Verdict.Found.class, verdict);
        assertEquals(104, found.x(), "取眼钻下去的落点");
        assertEquals(97, found.z());
        assertTrue(found.how().contains("往下钻"), found.how());
    }

    @Test
    void 第一扔沿飞向走第一段() {
        EyeBearings.Verdict verdict = EyeBearings.judge(null, flew(0, 0, 0, -12), false);

        EyeBearings.Verdict.Walk walk = assertInstanceOf(EyeBearings.Verdict.Walk.class, verdict);
        assertEquals(0, walk.dx(), 1e-9);
        assertEquals(-1, walk.dz(), 1e-9, "眼往北飞就往北走");
        assertEquals(EyeBearings.FIRST_LEG, walk.leg());
    }

    @Test
    void 一路指着同一方向就把下一段放长() {
        // 要塞在正北一千格开外：走了 80 格再扔，方向几乎不变。
        EyeFlight first = toward(0, 0, 3, -1000);
        EyeFlight second = toward(0, -80, 3, -1000);
        EyeBearings.Verdict verdict = EyeBearings.judge(first, second, false);

        EyeBearings.Verdict.Walk walk = assertInstanceOf(EyeBearings.Verdict.Walk.class, verdict);
        assertEquals(EyeBearings.MAX_LEG, walk.leg(), "离得远交点不作数，放长一段接着走");
    }

    @Test
    void 两次飞向交出明显角度就是要塞的位置() {
        // 要塞在 (60, -100)：第一次在原点扔，往北走 80 格再扔，眼往东北偏得很明显。
        EyeFlight first = toward(0, 0, 60, -100);
        EyeFlight second = toward(0, -80, 60, -100);
        EyeBearings.Verdict verdict = EyeBearings.judge(first, second, false);

        EyeBearings.Verdict.Found found = assertInstanceOf(EyeBearings.Verdict.Found.class, verdict);
        assertEquals(60, found.x(), 1);
        assertEquals(-100, found.z(), 1);
        assertTrue(found.how().contains("交"), found.how());
    }

    @Test
    void 交点太远不作数接着往眼指的方向走() {
        // 要塞在 (130, -400)：两次夹角十几度够交了，但交点离第二次出手点四百多格，误差大，先走近再扔。
        EyeFlight first = toward(0, 0, 130, -400);
        EyeFlight second = toward(-100, -40, 130, -400);
        EyeBearings.Verdict verdict = EyeBearings.judge(first, second, false);

        EyeBearings.Verdict.Walk walk = assertInstanceOf(EyeBearings.Verdict.Walk.class, verdict);
        assertTrue(walk.dx() > 0 && walk.dz() < 0, "照第二只眼的方向往东北走");
    }

    @Test
    void 走过头眼往回指就收短一半往回走() {
        // 要塞在 (0, -50)：在 (0, 0) 扔指北，走了 80 格到 (0, -80) 再扔，眼掉头指南。
        EyeFlight first = toward(0, 0, 0.2, -50);
        EyeFlight second = toward(0, -80, 0.2, -50);
        EyeBearings.Verdict verdict = EyeBearings.judge(first, second, false);

        EyeBearings.Verdict.Walk walk = assertInstanceOf(EyeBearings.Verdict.Walk.class, verdict);
        assertTrue(walk.dz() > 0.99, "往回（往南）走");
        assertEquals(40, walk.leg(), "走过头了，下一段收短一半");
    }

    @Test
    void 最短一段里还在来回指就取两次出手点的中点() {
        EyeFlight first = toward(0, 0, 0.1, -10);
        EyeFlight second = toward(0, -24, 0.1, -10);
        EyeBearings.Verdict verdict = EyeBearings.judge(first, second, false);

        EyeBearings.Verdict.Found found = assertInstanceOf(EyeBearings.Verdict.Found.class, verdict);
        assertEquals(0, found.x());
        assertEquals(-12, found.z());
        assertTrue(found.how().contains("之间"), found.how());
    }

    @Test
    void 眼几乎没挪动读不出方向() {
        EyeFlight still = new EyeFlight(0, 70, 0, 0.5, 70.5, 0.5, EyeFlight.Landing.DROPPED, 3);

        assertInstanceOf(EyeBearings.Verdict.Unreadable.class, EyeBearings.judge(null, still, false));
    }

    @Test
    void 开始偏了就收短下一段() {
        assertEquals(EyeBearings.MIN_LEG, EyeBearings.nextLeg(30, 8, false), "偏了收短一半，不短于最短一段");
        assertEquals(80, EyeBearings.nextLeg(160, 20, false));
        assertEquals(EyeBearings.MAX_LEG, EyeBearings.nextLeg(160, 1, false), "稳着就放长，不超过最长一段");
        assertEquals(40, EyeBearings.nextLeg(80, 1, true), "走过头过就一直收短，不再放长");
    }

    @Test
    void 走过头以后一路夹到眼往下钻() {
        // 要塞正好在出手的那条线上 (0, -116)：先走 80，再放长走 160 走过头，之后来回每段收短一半。
        double strongholdZ = -116;
        double z = 0;
        EyeFlight earlier = null;
        boolean overshot = false;
        for (int throwsLeft = 10; throwsLeft > 0; throwsLeft--) {
            double gap = strongholdZ - z;
            EyeFlight flight = Math.abs(gap) <= 12
                    ? new EyeFlight(0, 70, z, 0, 40, strongholdZ, EyeFlight.Landing.DROPPED, 1)
                    : flew(0, z, 0, Math.signum(gap) * 12);
            EyeBearings.Verdict verdict = EyeBearings.judge(earlier, flight, overshot);
            if (verdict instanceof EyeBearings.Verdict.Found found) {
                assertEquals(strongholdZ, found.z(), 12, "夹到了要塞附近：" + found);
                return;
            }
            EyeBearings.Verdict.Walk walk = assertInstanceOf(EyeBearings.Verdict.Walk.class, verdict);
            earlier = flight;
            overshot = walk.overshot();
            z += walk.dz() * walk.leg();
        }
        throw new AssertionError("十只眼都没夹到要塞，来回走成了死循环");
    }
}
