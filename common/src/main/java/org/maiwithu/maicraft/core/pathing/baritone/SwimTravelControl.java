package org.maiwithu.maicraft.core.pathing.baritone;

import org.maiwithu.maicraft.core.pathing.util.SwimAirBudget;

/** Physical swim phases shared by the live route controller and its deterministic regressions. */
public final class SwimTravelControl {
    enum Phase { OFF, DIVING, CRUISING, SURFACING, REFILL }

    private static final double DEPTH_DEADBAND = 0.12;
    final SwimAirBudget airBudget = new SwimAirBudget();
    private Phase phase = Phase.OFF;
    private double targetY;
    private double feetY;

    void update(boolean inWater, boolean onGround, boolean swimming, boolean eyesWet, double feetY,
                double eyeHeight, double waterSurface, double routeY, boolean deepRoute,
                boolean approachingShore, int air, int maxAir, int ascentReserve) {
        this.feetY = feetY;
        if (!inWater && onGround) {
            phase = Phase.OFF;
            return;
        }
        if (!inWater) {
            // An upward stroke can briefly lift the whole bounding box out of water. That is
            // still the same recovery episode, not a landing or permission to dive again.
            if (phase != Phase.OFF) phase = air < maxAir ? Phase.REFILL : Phase.OFF;
            return;
        }
        if (phase == Phase.SURFACING) {
            if (!eyesWet) phase = air < maxAir ? Phase.REFILL : Phase.OFF;
        } else if (phase == Phase.REFILL) {
            if (air >= maxAir) phase = Phase.OFF;
            else if (eyesWet) phase = Phase.SURFACING;
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
    boolean recovering() { return phase == Phase.SURFACING || phase == Phase.REFILL; }
    boolean sprinting() { return phase == Phase.DIVING || phase == Phase.CRUISING; }
    Phase phase() { return phase; }
    /** Structural path moves release depth tracking, but cannot erase unfinished air recovery. */
    void releaseRoute(boolean inWater, boolean onGround, int air, int maxAir) {
        if ((!inWater && onGround) || air >= maxAir || !recovering()) phase = Phase.OFF;
    }

    /** All route segments for one physical body share recovery; a respawn/world change does not. */
    public static final class BodyState {
        private Object body;
        private Object world;
        private SwimTravelControl control;

        public SwimTravelControl bind(Object body, Object world) {
            if (control == null || this.body != body || this.world != world) {
                this.body = body;
                this.world = world;
                control = new SwimTravelControl();
            }
            return control;
        }

        public void clear() {
            body = null;
            world = null;
            control = null;
        }
    }
}
