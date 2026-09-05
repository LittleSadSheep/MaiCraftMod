package org.maiwithu.maicraft.core.pathing.baritone;

/** Physical swim phases shared by the live route controller and its deterministic regressions. */
final class SwimTravelControl {
    enum Phase { OFF, DIVING, CRUISING, SURFACING, REFILL }

    private static final double DEPTH_DEADBAND = 0.12;
    private Phase phase = Phase.OFF;
    private double targetY;
    private double feetY;

    void update(boolean inWater, boolean swimming, boolean eyesWet, double feetY,
                double eyeHeight, double waterSurface, double routeY, boolean deepRoute,
                boolean approachingShore, int air, int maxAir, int ascentReserve) {
        this.feetY = feetY;
        if (!inWater) {
            phase = Phase.OFF;
            return;
        }
        if (phase == Phase.SURFACING) {
            if (!eyesWet) phase = air < maxAir ? Phase.REFILL : Phase.OFF;
        } else if (phase == Phase.REFILL) {
            if (eyesWet) phase = Phase.SURFACING;
            else if (air >= maxAir || !deepRoute) phase = Phase.OFF;
        } else if (phase != Phase.OFF
                && (air <= ascentReserve || !deepRoute || approachingShore)) {
            phase = Phase.SURFACING;
        } else if (phase == Phase.DIVING && swimming) {
            // Eye submersion precedes vanilla's swimming pose. Releasing the dive on that
            // earlier observation used to raise upright eyes straight back out of the water.
            phase = Phase.CRUISING;
        } else if (phase == Phase.CRUISING && !swimming) {
            phase = Phase.DIVING;
        }
        if (phase == Phase.OFF && deepRoute && !approachingShore && air > ascentReserve) {
            phase = swimming ? Phase.CRUISING : Phase.DIVING;
        }
        // A deliberate underwater route may end at a depth-specific goal. The swim pose can
        // lower a surface route, but must never lift that underwater route to a different Y.
        targetY = Math.min(routeY + 0.1,
                waterSurface - eyeHeight - (phase == Phase.DIVING ? 0.15 : 0.60));
    }

    int verticalIntent() {
        if (phase == Phase.OFF) return 0;
        if (phase == Phase.SURFACING || phase == Phase.REFILL) return 1;
        if (feetY > targetY + DEPTH_DEADBAND) return -1;
        if (feetY < targetY - DEPTH_DEADBAND) return 1;
        return 0;
    }

    float cameraPitch() {
        if (phase == Phase.DIVING) return 28.0F;
        if (phase == Phase.SURFACING || phase == Phase.REFILL) return -24.0F;
        return phase == Phase.CRUISING ? verticalIntent() * -8.0F : 0.0F;
    }

    boolean active() { return phase != Phase.OFF; }
    boolean surfacing() { return phase == Phase.SURFACING; }
    boolean sprinting() { return phase == Phase.DIVING || phase == Phase.CRUISING; }
    Phase phase() { return phase; }
    void reset() { phase = Phase.OFF; }

    void inheritFrom(SwimTravelControl previous) {
        phase = previous.phase;
        targetY = previous.targetY;
        feetY = previous.feetY;
    }
}
