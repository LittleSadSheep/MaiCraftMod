// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import org.maiwithu.maicraft.server.machine.NativeApi;

/** Rejects insufficient or destructive rotation before materials or construction are committed. */
public final class KineticRpmBudget {
    public record Check(double targetRpm, double maximumNewRpm) {}
    private static final double EPSILON = 1e-6;
    private KineticRpmBudget() {}

    /** Reads the installed/synchronized Create setting; absence is not replaced by an assumed default. */
    public static int maximumRotationSpeed() {
        try {
            Object config = NativeApi.call(null, "com.simibubi.create.infrastructure.config.AllConfigs", "server");
            Object kinetics = NativeApi.field(config, null, "kinetics");
            Object maximum = NativeApi.field(kinetics, null, "maxRotationSpeed");
            Object value = NativeApi.call(maximum, null, "get");
            if (!(value instanceof Number number) || number.intValue() < 1 || number.doubleValue() != number.intValue())
                throw new IllegalArgumentException("kinetic_native_rotation_limit_invalid");
            return number.intValue();
        } catch (RuntimeException | LinkageError unavailable) {
            throw new IllegalArgumentException("kinetic_native_rotation_limit_unavailable", unavailable);
        }
    }
    public static boolean accepts(KineticRouteGeometry.Plan plan, double sourceRpm, double targetMinimumRpm, double maximumRpm) {
        try { validate(plan, sourceRpm, targetMinimumRpm, maximumRpm); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }
    /** sourceRpm must describe the chosen outlet; existing directional shafts need their native outlet correction. */
    public static Check validate(KineticRouteGeometry.Plan plan, double sourceRpm, double targetMinimumRpm, double maximumRpm) {
        if (plan == null || !Double.isFinite(sourceRpm) || sourceRpm == 0) throw bad("kinetic_source_rpm_unavailable");
        if (!Double.isFinite(targetMinimumRpm) || targetMinimumRpm < 0) throw bad("kinetic_target_rpm_requirement_invalid");
        if (!Double.isFinite(maximumRpm) || maximumRpm <= 0) throw bad("kinetic_rotation_speed_limit_unavailable");
        var ratios = KineticTransmissionRatios.calculate(plan);
        if (ratios.multiplier() == null) throw bad("kinetic_route_ratio_unverified");
        double target = sourceRpm * ratios.multiplier();
        if (!Double.isFinite(target) || Math.abs(target) > maximumRpm + EPSILON) throw bad("kinetic_target_overspeed");
        if (Math.abs(target) + EPSILON < targetMinimumRpm) throw bad("kinetic_target_rpm_below_requirement");
        double maximumNew = 0;
        for (var placement : plan.placements()) {
            Double multiplier = ratios.nodeMultipliers().get(placement.position());
            if (multiplier == null) throw bad("kinetic_route_ratio_unverified");
            double rpm = Math.abs(sourceRpm * multiplier);
            if (!Double.isFinite(rpm) || rpm > maximumRpm + EPSILON) throw bad("kinetic_new_node_overspeed");
            maximumNew = Math.max(maximumNew, rpm);
        }
        return new Check(target, maximumNew);
    }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
