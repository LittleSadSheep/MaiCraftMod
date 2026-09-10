// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

/**
 * 用一组固定背包参数检查竖直位移、重力后的速度、一次推力的最高点，以及长期悬停和上升能否保持高度范围；不加载真实飞行模组。
 */
public final class JetpackDynamicsTest {
    private static final JetpackNativeAdapter.Snapshot POWER = new JetpackNativeAdapter.Snapshot(
            true, "CreateJetpack 5.1.2 / FlightLib 3.2.1", "create_jetpack:netherite_jetpack", true, true,
            900, 17000, 0.016, 0.32, 0.6, -0.03, 0.08);

    public static void main(String[] args) {
        double rawHover = JetpackDynamics.rawAfterStep(-0.03, POWER);
        close(rawHover, -0.1078, "observed hover raw velocity");
        close(JetpackDynamics.nextVertical(rawHover, false, POWER), -0.03, "hover movement is not raw tick-end velocity");
        close(JetpackDynamics.nextVertical(rawHover, true, POWER), 0.32, "native UP clamps to hover vertical speed");
        close(JetpackDynamics.coastRise(rawHover, POWER), 0, "falling hover has no upward coast");
        close(JetpackDynamics.riseEnvelope(rawHover, true, POWER), 0.77795008, "one UP pulse includes its positive coasting tail");
        double altitude = 0, raw = rawHover, peak = 0;
        for (int tick = 0; tick < 20; tick++) {
            double step = JetpackDynamics.nextVertical(raw, tick == 0, POWER);
            altitude += step; raw = JetpackDynamics.rawAfterStep(step, POWER);
            peak = Math.max(peak, altitude);
        }
        close(peak, JetpackDynamics.riseEnvelope(rawHover, true, POWER), "envelope did not bound simulated native pulse");
        check(!JetpackDynamics.shouldRise(115.20, rawHover, 115, POWER), "raw gravity caused an unnecessary pulse above cruise height");
        check(JetpackDynamics.shouldRise(115.01, rawHover, 115, POWER), "hover was allowed to sink below minimum cruise height");
        check(!JetpackDynamics.shouldRise(114.8, 0.2352, 115, POWER), "existing upward coast was ignored");
        boundedCruise(rawHover);
        climbToPlatform(rawHover);
        check(!JetpackDynamics.shouldRise(115, -2, 116, POWER), "UP worsened a fast fall instead of letting native hover brake");
        double braked = JetpackDynamics.rawAfterStep(JetpackDynamics.nextVertical(-2, false, POWER), POWER);
        check(JetpackDynamics.shouldRise(115, braked, 116, POWER), "climb did not resume after native hover braking");
        try {
            JetpackDynamics.nextVertical(Double.NaN, false, POWER);
            throw new AssertionError("unknown physical velocity accepted");
        } catch (IllegalArgumentException expected) {}
        System.out.println("JetpackDynamicsTest: passed");
    }

    // 连续三千次使用同一组公式推进高度，检查不会持续下沉或越升越高；这是模型内的长期稳定性检查。
    private static void boundedCruise(double raw) {
        double height = 115, minimum = height;
        double envelope = JetpackDynamics.riseEnvelope(raw, true, POWER) - POWER.hoverDescent();
        int pulses = 0;
        for (int tick = 0; tick < 3000; tick++) {
            boolean up = JetpackDynamics.shouldRise(height, raw, minimum, POWER);
            if (up) pulses++;
            double step = JetpackDynamics.nextVertical(raw, up, POWER);
            height += step; raw = JetpackDynamics.rawAfterStep(step, POWER);
            check(height >= minimum - 1e-9 && height <= minimum + envelope + 1e-9,
                    "cruise escaped its native pulse envelope at tick " + tick + ": " + height);
        }
        check(pulses > 1 && pulses < 150, "cruise failed to balance sparse UP pulses and slow hover descent");
    }

    private static void climbToPlatform(double raw) {
        double height = 101;
        int reached = -1;
        for (int tick = 0; tick < 1000; tick++) {
            boolean up = JetpackDynamics.shouldRise(height, raw, 115, POWER);
            double step = JetpackDynamics.nextVertical(raw, up, POWER);
            height += step; raw = JetpackDynamics.rawAfterStep(step, POWER);
            if (height >= 115 && reached < 0) reached = tick;
            if (reached >= 0) check(height >= 115 - 1e-9 && height < 115.81, "climb failed to settle into the upper platform cruise band");
        }
        check(reached > 0 && reached < 60, "sustained ascent did not reach the higher platform");
    }

    private static void close(double actual, double expected, String reason) { check(Math.abs(actual - expected) < 1e-9, reason + ": " + actual); }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
