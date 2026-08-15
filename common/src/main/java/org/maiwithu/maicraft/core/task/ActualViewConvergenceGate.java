// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/**
 * Per-action proof that a deferred look request crossed the actor's {@code endTick} boundary and
 * the real player camera, rather than a requested yaw/pitch, now faces the intended direction.
 *
 * <p>Callers submit their look request every tick, then ask this gate whether the current view may
 * be trusted for a raytrace or direction-sensitive native action. A new target/action must call
 * {@link #reset()} before its first request.</p>
 */
public final class ActualViewConvergenceGate {
    private static final double MINIMUM_DOT = Math.cos(Math.toRadians(1.0D));

    private long firstRequestRevision = Long.MIN_VALUE;

    public boolean ready(LocalPlayer player, Vec3 desiredDirection) {
        long revision = ClientRuntime.requireContext(player).tickRevision();
        if (firstRequestRevision == Long.MIN_VALUE) {
            firstRequestRevision = revision;
            return false;
        }
        if (revision <= firstRequestRevision) return false;
        return aligned(player.getViewVector(1.0F), desiredDirection);
    }

    public void reset() {
        firstRequestRevision = Long.MIN_VALUE;
    }

    private static boolean aligned(Vec3 currentLook, Vec3 desiredDirection) {
        if (desiredDirection.lengthSqr() < 1.0e-8D) return true;
        if (currentLook.lengthSqr() < 1.0e-8D) return false;
        return currentLook.normalize().dot(desiredDirection.normalize()) >= MINIMUM_DOT;
    }
}
