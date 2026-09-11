// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import java.util.List;
import org.maiwithu.maicraft.core.integration.machine.MachineBuildTaskRecord;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** One production goal can include construction, but finishes only after its output observation succeeds. */
public final class MachineProductionTaskRecord extends TaskRecord implements InternalPositionReceipt {
    static { TaskFactory.register(MachineProductionTaskRecord.class, MachineProductionTask::new); }
    public final ProductionRunPlan plan;
    public final MachineBuildTaskRecord construction;
    public final List<String> protectedLabels;
    public final org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy toolPolicy;
    private Position verified;

    public MachineProductionTaskRecord(String callId, long deadline, ProductionRunPlan plan,
                                       MachineBuildTaskRecord construction, List<String> protectedLabels) {
        this(callId, deadline, plan, construction, protectedLabels, construction == null
                ? org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY : construction.materialPolicy);
    }

    public MachineProductionTaskRecord(String callId, long deadline, ProductionRunPlan plan,
            MachineBuildTaskRecord construction, List<String> protectedLabels,
            org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy toolPolicy) {
        super("run_machine_production", callId, deadline);
        this.plan = java.util.Objects.requireNonNull(plan); this.construction = construction;
        this.protectedLabels = List.copyOf(protectedLabels);
        this.toolPolicy = java.util.Objects.requireNonNull(toolPolicy);
        if (construction != null && (!construction.dimension.equals(plan.dimension())
                || !construction.plan.anchor().equals(plan.anchor())))
            throw new IllegalArgumentException("Production must use the exact frozen construction anchor and dimension");
    }

    void verified() { var at = plan.anchor(); verified = new Position(at.getX(), at.getY(), at.getZ(), plan.dimension()); }
    @Override public Position internalVerifiedPosition() { return verified; }
    @Override public String describe() { return "机器生产 · " + plan.manifest().target().resource().id(); }
}
