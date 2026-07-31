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

    void requestLook(float yaw, float pitch, long leaseTickRevision);

    void clearLook();

    /** Immediately zero every injected signal. This never sends an interaction packet. */
    void releaseAll();
}
