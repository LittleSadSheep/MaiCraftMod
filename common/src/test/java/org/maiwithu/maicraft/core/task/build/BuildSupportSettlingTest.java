// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import net.minecraft.world.phys.Vec3;

/** 回放实机下台阶和余速数据；原版站地仍有 -0.0784 垂直速度，不能把它当成永远没有站稳。 */
public final class BuildSupportSettlingTest {
    public static void main(String[] args) {
        var gate = new BuildSupportSettling();
        double[][] samples = samples();
        for (int i = 0; i < samples.length; i++) {
            double[] value = samples[i];
            var status = gate.observe(new Vec3(value[0], value[1], -1.5526316205109494),
                    new Vec3(value[2], -.0784000015258789, 0), value[3] == 1, true, 109366 + i);
            check(status == (i < samples.length - 1 ? BuildSupportSettling.Status.WAITING : BuildSupportSettling.Status.READY),
                    "wait through the actual fall and horizontal inertia, then accept the stable ground samples");
        }
        var duplicate = new BuildSupportSettling(); Vec3 feet = new Vec3(.5, 1, .5), gravity = new Vec3(0, -.0784, 0);
        for (int i = 0; i < 1000; i++) check(duplicate.observe(feet, gravity, true, true, 1) == BuildSupportSettling.Status.WAITING,
                "repeating one game tick cannot prove physical settling");
        check(duplicate.observe(feet, gravity, true, true, 2) == BuildSupportSettling.Status.WAITING
                && duplicate.observe(feet, gravity, true, true, 3) == BuildSupportSettling.Status.READY,
                "ordinary standing gravity is compatible with two consecutive stationary transitions");
        var moving = new BuildSupportSettling();
        for (int tick = 0; tick < BuildSupportSettling.MAX_WAIT_TICKS; tick++) {
            var status = moving.observe(new Vec3(tick * .1, 1, .5), new Vec3(.1, -.0784, 0), true, true, tick);
            check(status == (tick + 1 == BuildSupportSettling.MAX_WAIT_TICKS ? BuildSupportSettling.Status.FAILED : BuildSupportSettling.Status.WAITING),
                    "continuous drift exhausts a fixed wait instead of renewing forever");
        }
        var vehicle = new BuildSupportSettling();
        for (int tick = 0; tick < 4; tick++) check(vehicle.observe(feet, gravity, true, false, tick) == BuildSupportSettling.Status.WAITING,
                "being stationary in water or on a vehicle does not prove ordinary walking footing");
        System.out.println("BuildSupportSettlingTest: passed");
    }
    static double[][] samples() {
        return new double[][] {
                {-8.209546673578666, 72.53584062504456, .11111201978633872, 0},
                {-8.098434653792328, 72.23152379758702, .10111194091959692, 0},
                {-7.997322712872731, 72, .09201186888859939, 1},
                {-7.9053108439841315, 72, .05023848624850745, 1},
                {-7.855072357735624, 72, .02743021667777681, 1},
                {-7.827642141057847, 72, .014976900045672432, 1},
                {-7.812665241012175, 72, .008177388374762295, 1},
                {-7.804487852637412, 72, .0044648545712248034, 1},
                {-7.800022998066187, 72, .002437810879046882, 1}
        };
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
