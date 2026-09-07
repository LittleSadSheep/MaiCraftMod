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

/** Final closures of temporary two-high construction entrances after the bulk builder cleans up. */
public final class MachineSealingTaskRecord extends TaskRecord {
    static { TaskFactory.register(MachineSealingTaskRecord.class, MachineSealingTask::new); }
    public record Seal(List<BuildTaskRecord.Target> targets, BlockPos outside) {
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
        this.fullPlanCells = fullPlanCells.stream().map(BlockPos::immutable).distinct().toList();
    }
    @Override public String describe() { return "从外侧封闭 " + seals.size() + " 个机器施工口"; }
}
