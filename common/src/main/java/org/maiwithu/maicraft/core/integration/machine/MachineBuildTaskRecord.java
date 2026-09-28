// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.List;
import java.util.Objects;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 保存整套机器装配计划、所在维度、取材策略和保护标签；原生施工结束后保留现场位置，供继续观察和修改。
 */
public final class MachineBuildTaskRecord extends TaskRecord implements InternalPositionReceipt {
    static { TaskFactory.register(MachineBuildTaskRecord.class, MachineBuildTask::new); }
    public final MachineConstructionPlan plan;
    public final String dimension;
    public final MaterialPolicy materialPolicy;
    public final List<String> protectedLabels;
    public final String label;
    private Position verified;

    public MachineBuildTaskRecord(String callId, long deadline, MachineConstructionPlan plan,
            String dimension, MaterialPolicy materialPolicy, List<String> protectedLabels) {
        this(callId, deadline, plan, dimension, materialPolicy, protectedLabels, null);
    }
    public MachineBuildTaskRecord(String callId, long deadline, MachineConstructionPlan plan,
            String dimension, MaterialPolicy materialPolicy, List<String> protectedLabels, String label) {
        super("build_machine", callId, deadline);
        this.plan = Objects.requireNonNull(plan); this.dimension = Objects.requireNonNull(dimension);
        this.materialPolicy = Objects.requireNonNull(materialPolicy); this.protectedLabels = List.copyOf(protectedLabels);
        // 工地名称跟随实际施工单，完工后自动成为机器档案名称，后续无需重新猜测坐标。
        this.label = label;
    }

    // 记录本次实际施工的锚点，供回看和修改；不证明蓝图匹配或生产成功，也不表示角色站在这里。
    void verified() {
        var at = plan.anchor(); verified = new Position(at.getX(), at.getY(), at.getZ(), dimension);
    }
    @Override public Position internalVerifiedPosition() { return verified; }
    @Override public String describe() { return "建造机器 · " + plan.blocks().size() + " 格及 " + plan.parts().size() + " 个部件"; }
}
