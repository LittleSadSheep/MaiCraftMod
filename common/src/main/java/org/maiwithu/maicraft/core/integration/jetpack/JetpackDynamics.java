// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

/** Airborne UPRIGHT hover model for CreateJetpack tag 5.1.2 / installed FlightLib 3.2.1.
 * JetpackLogic.uprightMovement runs before movement; ordinary MC air travel applies gravity
 * and 0.98 vertical drag afterwards. Raw tick-end velocity is therefore not next tick's displacement.
 * This assumes the native active context remains usable: fluids, elytra, levitation and supported
 * vanilla jumps need separate handling. It neither changes entity velocity nor emits a sneak input.
 */
public final class JetpackDynamics {
    private static final double AIR_DRAG = 0.98;
    private JetpackDynamics() {}

    /** Vertical displacement after the next native UP/released-UP decision, before gravity. */
    public static double nextVertical(double rawVy, boolean up, JetpackNativeAdapter.Snapshot power) {
        requireModel(rawVy, power);
        return up ? Math.min(rawVy + power.acceleration(), power.vertical()) : Math.max(rawVy, power.hoverDescent());
    }

    /** Tick-end velocity after ordinary airborne movement. */
    public static double rawAfterStep(double displacement, JetpackNativeAdapter.Snapshot power) {
        requireModel(displacement, power);
        return (displacement - power.gravity()) * AIR_DRAG;
    }

    /** Remaining positive displacement if UP stays released; slow downward hover adds no rise. */
    public static double coastRise(double rawVy, JetpackNativeAdapter.Snapshot power) {
        requireModel(rawVy, power);
        double rise = 0;
        for (int tick = 0; tick < 512 && rawVy > 0; tick++) {
            rise += rawVy;
            rawVy = rawAfterStep(rawVy, power);
        }
        // Extremely small configured gravity may leave a tail: ignoring gravity bounds it above.
        return rise + Math.max(0, rawVy) / (1 - AIR_DRAG);
    }

    /** Peak rise of this input pulse and subsequent released-UP coasting, relative to current feet. */
    public static double riseEnvelope(double rawVy, boolean up, JetpackNativeAdapter.Snapshot power) {
        double step = nextVertical(rawVy, up, power);
        return Math.max(0, step) + coastRise(rawAfterStep(step, power), power);
    }

    /** Keep a minimum cruise height, allowing the unavoidable native UP-pulse/coasting band above it. */
    public static boolean shouldRise(double height, double rawVy, double minimumHeight, JetpackNativeAdapter.Snapshot power) {
        if (!Double.isFinite(height) || !Double.isFinite(minimumHeight)) throw new IllegalArgumentException("finite flight heights required");
        double releasedStep = nextVertical(rawVy, false, power);
        // During an externally induced fast fall, hover clamps descent immediately; UP may not.
        if (nextVertical(rawVy, true, power) <= releasedStep) return false;
        double releasedReach = releasedStep > 0 ? coastRise(rawVy, power) : releasedStep;
        return height + releasedReach < minimumHeight;
    }

    private static void requireModel(double value, JetpackNativeAdapter.Snapshot power) {
        if (!Double.isFinite(value) || power == null || !Double.isFinite(power.gravity()) || power.gravity() <= 0
                || !Double.isFinite(power.acceleration()) || power.acceleration() <= 0
                || !Double.isFinite(power.vertical()) || power.vertical() <= 0
                || !Double.isFinite(power.hoverDescent()) || power.hoverDescent() > 0) {
            throw new IllegalArgumentException("finite native upright flight parameters required");
        }
    }
}
