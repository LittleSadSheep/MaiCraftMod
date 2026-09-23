// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import org.maiwithu.maicraft.task.TaskRecord;
import java.util.Objects;

/** 内部传送门准备子任务；真实传送门方块格绝不会作为坐标交由模型指定。 */
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
        this.policy = Objects.requireNonNull(policy);
    }
    @Override public String describe() { return "prepare a portal to " + destination; }
}
