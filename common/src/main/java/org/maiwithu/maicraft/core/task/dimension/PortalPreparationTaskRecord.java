// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import org.maiwithu.maicraft.task.TaskRecord;

/** Private preparation child; actual portal cells never become model-supplied coordinates. */
public final class PortalPreparationTaskRecord extends TaskRecord {
    final String destination;
    final int radius;
    final boolean mayAlterTerrain;
    final PortalPreparationPolicy policy;

    public PortalPreparationTaskRecord(String callId, long deadline, String destination, int radius,
                                       boolean mayAlterTerrain, PortalPreparationPolicy policy) {
        super("prepare_portal", callId, deadline);
        this.destination = destination;
        this.radius = Math.clamp(radius, 16, 512);
        this.mayAlterTerrain = mayAlterTerrain;
        this.policy = java.util.Objects.requireNonNull(policy);
    }
    @Override public String describe() { return "prepare a portal to " + destination; }
}
