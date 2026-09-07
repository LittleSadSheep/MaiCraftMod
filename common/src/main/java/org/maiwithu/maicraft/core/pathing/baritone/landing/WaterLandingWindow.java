package org.maiwithu.maicraft.core.pathing.baritone.landing;

/** Frozen native dry-fall physics; a bucket needs a reachable tick before support collision. */
public record WaterLandingWindow(double gravity, double reach, double eyeHeight, double initialDownwardSpeed) {
    public boolean permits(double drop) {
        if (!Double.isFinite(drop) || drop <= 0 || !Double.isFinite(gravity) || gravity <= 0
                || !Double.isFinite(reach) || reach <= 0 || !Double.isFinite(eyeHeight) || eyeHeight <= 0
                || !Double.isFinite(initialDownwardSpeed) || initialDownwardSpeed < 0) return false;
        // Reserve the full horizontal distance from a cell corner to the clicked face center.
        double window = Math.sqrt(Math.max(0, reach * reach - 0.5)) - eyeHeight;
        if (window <= 0) return false;
        double remaining = drop, speed = initialDownwardSpeed;
        for (int tick = 0; tick < 4096; tick++) {
            // LivingEntity travels with this tick's velocity, then applies gravity and drag.
            // Requiring every step to fit in the reach window covers the unknown departure
            // phase; accepting one lucky height sample would permit a one-tick overshoot.
            if (speed >= window) return false;
            remaining -= speed;
            if (remaining <= 0) return true;
            speed = (speed + gravity) * (double) 0.98F;
        }
        return false;
    }
}
