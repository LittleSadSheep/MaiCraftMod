// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 保存要封闭的临时施工洞口、外侧站位、材料策略和需要保护的完整机器范围。
 */
public final class MachineSealingTaskRecord extends TaskRecord {
    static { TaskFactory.register(MachineSealingTaskRecord.class, MachineSealingTask::new); }
    public record Seal(List<BuildTaskRecord.Target> targets, BlockPos outside) {
        // 一个洞口必须恰好有上下相邻的两个非空气目标；外侧站位在水平上必须正对其中一个面，不能斜对角。
        public Seal {
            targets = targets.stream().sorted(BuildOrder.BUILD_ORDER).toList();
            outside = Objects.requireNonNull(outside).immutable();
            if (targets.size() != 2 || targets.stream().anyMatch(target -> target.desiredState().isAir())
                    || !targets.get(0).pos().above().equals(targets.get(1).pos()))
                throw new IllegalArgumentException("machine seal must contain exactly two vertically adjacent final solid targets");
            BlockPos door = targets.getFirst().pos();
            int dx = outside.getX() - door.getX(), dz = outside.getZ() - door.getZ();
            if ((dx == 0) == (dz == 0))
                throw new IllegalArgumentException("machine seal outside stance must align with one exterior face");
        }
        public Direction outward() {
            BlockPos door = targets.getFirst().pos();
            int dx = outside.getX() - door.getX(), dz = outside.getZ() - door.getZ();
            return dx < 0 ? Direction.WEST : dx > 0 ? Direction.EAST : dz < 0 ? Direction.NORTH : Direction.SOUTH;
        }
        // 按洞口朝外的方向算距离，身体中心必须超出洞口中心 0.8 格；这里只判侧别，不限制高度或横向偏移。
        public boolean onOutside(Vec3 body) {
            Vec3 center = Vec3.atCenterOf(targets.getFirst().pos());
            Direction direction = outward();
            return (body.x - center.x) * direction.getStepX()
                    + (body.z - center.z) * direction.getStepZ() > .8;
        }
        public boolean isOpening(BlockPos pos) { return targets.stream().anyMatch(target -> target.pos().equals(pos)); }
    }

    final List<Seal> seals;
    final MaterialPolicy materialPolicy;
    final List<String> protectedLabels;
    final List<BlockPos> fullPlanCells;

    public MachineSealingTaskRecord(String callId, long deadline, List<Seal> seals,
            MaterialPolicy materialPolicy, List<String> protectedLabels, List<BlockPos> fullPlanCells) {
        super("seal_machine", callId, deadline);
        this.seals = List.copyOf(seals);
        this.materialPolicy = Objects.requireNonNull(materialPolicy);
        this.protectedLabels = List.copyOf(protectedLabels);
        // 保护坐标复制并去重，避免外部列表改变后意外影响后续封口。
        this.fullPlanCells = fullPlanCells.stream().map(BlockPos::immutable).distinct().toList();
    }
    @Override public String describe() { return "从外侧封闭 " + seals.size() + " 个机器施工口"; }
}
