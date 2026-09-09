// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.List;
import java.util.Objects;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 保存整套机器装配计划、所在维度、取材策略和保护标签；完成验收之后才记下可对外使用的位置。
 */
public final class MachineBuildTaskRecord extends TaskRecord implements InternalPositionReceipt {
    static { TaskFactory.register(MachineBuildTaskRecord.class, MachineBuildTask::new); }
    public final MachineConstructionPlan plan;
    public final String dimension;
    public final MaterialPolicy materialPolicy;
    public final List<String> protectedLabels;
    private Position verified;

    public MachineBuildTaskRecord(String callId, long deadline, MachineConstructionPlan plan,
            String dimension, MaterialPolicy materialPolicy, List<String> protectedLabels) {
        super("build_machine", callId, deadline);
        this.plan = Objects.requireNonNull(plan); this.dimension = Objects.requireNonNull(dimension);
        this.materialPolicy = Objects.requireNonNull(materialPolicy); this.protectedLabels = List.copyOf(protectedLabels);
    }

    // 记录已验收机器的锚点，表示结构所在位置，不表示角色正站在这个坐标。
    void verified() {
        var at = plan.anchor(); verified = new Position(at.getX(), at.getY(), at.getZ(), dimension);
    }
    @Override public Position internalVerifiedPosition() { return verified; }
    @Override public String describe() { return "建造机器 · " + plan.blocks().size() + " 格及 " + plan.parts().size() + " 个部件"; }
}
