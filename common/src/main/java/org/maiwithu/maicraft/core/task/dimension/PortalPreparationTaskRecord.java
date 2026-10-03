// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskFactory;
import java.util.Objects;

/** 内部传送门准备子任务；真实传送门方块格绝不会作为坐标交由模型指定。 */
public final class PortalPreparationTaskRecord extends TaskRecord {
    // 独立备门只负责真实建成和点火，不让“建一扇门”隐含授权角色穿门旅行。
    static { TaskFactory.register(PortalPreparationTaskRecord.class, PortalPreparationTask::new); }
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
