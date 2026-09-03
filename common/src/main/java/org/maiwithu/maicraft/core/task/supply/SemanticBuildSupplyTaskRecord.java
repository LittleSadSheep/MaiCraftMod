// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.supply;

import java.util.List;
import java.util.Objects;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskRecord;

/** Retains one reviewed build while generic semantic supply and carried-material batches execute. */
public final class SemanticBuildSupplyTaskRecord extends TaskRecord
        implements InternalPositionReceipt {
    public final BuildTaskRecord plan;
    public final SemanticMaterialSupplyCoordinator.MaterialPolicy materialPolicy;
    public final List<SemanticAcquireTaskRecord.Source> allowedSources;
    public final boolean allowHarm;
    public final List<String> protectedLabels;
    /** False only when the player explicitly requested the concrete preferred palette. */
    public final boolean broadenMaterialFamilies;
    private Position verifiedPosition;

    static {
        TaskFactory.register(SemanticBuildSupplyTaskRecord.class,
                SemanticBuildSupplyCompanionTask::new);
    }

    public SemanticBuildSupplyTaskRecord(
            String toolCallId, long deadlineGameTime, BuildTaskRecord plan) {
        this(toolCallId, deadlineGameTime, plan,
                SemanticMaterialSupplyCoordinator.MaterialPolicy.ORDINARY,
                List.of(), false, List.of(), true);
    }

    public SemanticBuildSupplyTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            BuildTaskRecord plan,
            SemanticMaterialSupplyCoordinator.MaterialPolicy materialPolicy,
            List<SemanticAcquireTaskRecord.Source> allowedSources,
            boolean allowHarm,
            List<String> protectedLabels,
            boolean broadenMaterialFamilies) {
        super(BuildTaskRecord.TOOL_NAME, toolCallId, deadlineGameTime);
        this.plan = Objects.requireNonNull(plan, "plan");
        this.materialPolicy = materialPolicy == null
                ? SemanticMaterialSupplyCoordinator.MaterialPolicy.ORDINARY : materialPolicy;
        this.allowedSources = allowedSources == null ? List.of() : List.copyOf(allowedSources);
        this.allowHarm = allowHarm;
        this.protectedLabels = protectedLabels == null ? List.of() : List.copyOf(protectedLabels);
        this.broadenMaterialFamilies = broadenMaterialFamilies;
    }

    public static void ensureRegistered() {}

    void retainVerifiedPosition(Position position) {
        verifiedPosition = position;
    }

    @Override
    public Position internalVerifiedPosition() {
        return verifiedPosition;
    }

    @Override
    public String describe() {
        return "搭建并按语义材料策略分批供应 " + plan.targets.size() + " 格";
    }
}
