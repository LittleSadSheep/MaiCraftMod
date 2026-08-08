// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.supply;

import java.util.Objects;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** Retains one reviewed build while AE supply and carried-material batches execute. */
public final class SemanticBuildSupplyTaskRecord extends TaskRecord {
    public final BuildTaskRecord plan;

    static {
        TaskFactory.register(SemanticBuildSupplyTaskRecord.class,
                SemanticBuildSupplyCompanionTask::new);
    }

    public SemanticBuildSupplyTaskRecord(
            String toolCallId, long deadlineGameTime, BuildTaskRecord plan) {
        super(BuildTaskRecord.TOOL_NAME, toolCallId, deadlineGameTime);
        this.plan = Objects.requireNonNull(plan, "plan");
    }

    public static void ensureRegistered() {}

    @Override
    public String describe() {
        return "搭建并从 AE 分批供应 " + plan.targets.size() + " 格";
    }
}
