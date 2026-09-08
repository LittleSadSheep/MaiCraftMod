// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

/** First-person movement and camera surface. Every command is leased for one client tick. */
public interface BodyControlPort {
    record Movement(float forward, float strafe, boolean jumping, boolean sneaking, boolean sprinting) {
        public static final Movement STOPPED = new Movement(0.0f, 0.0f, false, false, false);

        public Movement {
            if (!Float.isFinite(forward) || forward < -1.0f || forward > 1.0f) {
                throw new IllegalArgumentException("forward must be finite and between -1 and 1");
            }
            if (!Float.isFinite(strafe) || strafe < -1.0f || strafe > 1.0f) {
                throw new IllegalArgumentException("strafe must be finite and between -1 and 1");
            }
        }
    }

    boolean automationOwnsControls();

    void applyMovement(Movement movement, long leaseTickRevision);

    /** Re-evaluated against the physical camera yaw while its one-tick lease remains valid. */
    @FunctionalInterface
    interface Steering { Movement atYaw(float yaw); }

    default void applySteering(Steering steering, float currentYaw, long leaseTickRevision) {
        applyMovement(steering.atYaw(currentYaw), leaseTickRevision);
    }

    void requestLook(float yaw, float pitch, long leaseTickRevision);

    /** A time-critical interaction needs its real camera ray aligned during this actor tick. */
    default void requestImmediateLook(float yaw, float pitch, long leaseTickRevision) {
        requestLook(yaw,pitch,leaseTickRevision);
    }

    void clearLook();

    /** Immediately zero every injected signal. This never sends an interaction packet. */
    void releaseAll();
}
