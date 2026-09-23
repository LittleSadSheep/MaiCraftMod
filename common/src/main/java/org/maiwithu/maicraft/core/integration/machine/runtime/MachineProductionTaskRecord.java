// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import java.util.List;
import org.maiwithu.maicraft.core.integration.machine.MachineBuildTaskRecord;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import java.util.Objects;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;

/** 一个生产目标可以包含施工步骤，但只有产物观察成功后才算完成。 */
public final class MachineProductionTaskRecord extends TaskRecord implements InternalPositionReceipt {
    static { TaskFactory.register(MachineProductionTaskRecord.class, MachineProductionTask::new); }
    public final ProductionRunPlan plan;
    public final MachineBuildTaskRecord construction;
    public final List<String> protectedLabels;
    public final SemanticMaterialSupplyCoordinator.MaterialPolicy toolPolicy;
    private Position verified;

    public MachineProductionTaskRecord(String callId, long deadline, ProductionRunPlan plan,
                                       MachineBuildTaskRecord construction, List<String> protectedLabels) {
        this(callId, deadline, plan, construction, protectedLabels, construction == null
                ? SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY : construction.materialPolicy);
    }

    public MachineProductionTaskRecord(String callId, long deadline, ProductionRunPlan plan,
            MachineBuildTaskRecord construction, List<String> protectedLabels,
            SemanticMaterialSupplyCoordinator.MaterialPolicy toolPolicy) {
        super("run_machine_production", callId, deadline);
        this.plan = Objects.requireNonNull(plan); this.construction = construction;
        this.protectedLabels = List.copyOf(protectedLabels);
        this.toolPolicy = Objects.requireNonNull(toolPolicy);
        if (construction != null && (!construction.dimension.equals(plan.dimension())
                || !construction.plan.anchor().equals(plan.anchor())))
            throw new IllegalArgumentException("Production must use the exact frozen construction anchor and dimension");
    }

    void verified() { var at = plan.anchor(); verified = new Position(at.getX(), at.getY(), at.getZ(), plan.dimension()); }
    @Override public Position internalVerifiedPosition() { return verified; }
    @Override public String describe() { return "机器生产 · " + plan.manifest().target().resource().id(); }
}
