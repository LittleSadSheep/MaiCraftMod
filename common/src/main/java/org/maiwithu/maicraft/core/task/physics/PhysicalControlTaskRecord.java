package org.maiwithu.maicraft.core.task.physics;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** 配置始终绑定同一世界或结构中的明确部件，恢复观察不能把它替换成附近另一台控制器。 */
public final class PhysicalControlTaskRecord extends TaskRecord {
    static {TaskFactory.register(PhysicalControlTaskRecord.class,PhysicalControlTask::new);}
    final PhysicalControlParameters parameters;final BlockPos anchor;final String dimension;
    public PhysicalControlTaskRecord(String call,long deadline,PhysicalControlParameters parameters,BlockPos anchor,String dimension) {
        super("physical_control",call,deadline);this.parameters=parameters;this.anchor=anchor.immutable();this.dimension=dimension;
    }
    @Override public String describe(){return "通过原生界面设置物理部件并核对实际配置";}
}
