package org.maiwithu.maicraft.core.task.physics;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** 记录作者指定的胶种与选区，世界锚点不能替换为附近另一台机器。 */
public final class PhysicalBondTaskRecord extends TaskRecord {
    static { TaskFactory.register(PhysicalBondTaskRecord.class,PhysicalBondTask::new); }
    final PhysicalAssemblyParameters parameters;
    final BlockPos anchor;
    final String dimension;
    public PhysicalBondTaskRecord(String call,long deadline,PhysicalAssemblyParameters parameters,BlockPos anchor,String dimension) {
        super("physical_bond",call,deadline);this.parameters=parameters;this.anchor=anchor.immutable();this.dimension=dimension;
        if(parameters.operation()!=PhysicalAssemblyParameters.Operation.BOND)throw new IllegalArgumentException("粘接任务只接受 bond 操作");
    }
    @Override public String describe() { return "用"+(parameters.adhesive()==PhysicalAssemblyParameters.Adhesive.HONEY?"蜂蜜胶":"强力胶")+"粘接指定结构选区"; }
}
