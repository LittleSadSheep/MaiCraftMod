package org.maiwithu.maicraft.core.task.physics;

import net.minecraft.core.BlockPos;
import java.util.Locale;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** 保存选定的组装器及世界/船体坐标系，原生输入只执行一次，完成后用真实转换继续追踪设计。 */
public final class PhysicalAssemblerTaskRecord extends TaskRecord {
    static {TaskFactory.register(PhysicalAssemblerTaskRecord.class,PhysicalAssemblerTask::new);}
    final PhysicalAssemblyParameters parameters;
    final BlockPos anchor;
    final String dimension;
    public PhysicalAssemblerTaskRecord(String call,long deadline,PhysicalAssemblyParameters parameters,BlockPos anchor,String dimension) {
        super("physical_assembler",call,deadline);this.parameters=parameters;this.anchor=anchor.immutable();this.dimension=dimension;
    }
    @Override public String describe() {return switch(routine()) {case "assemble"->"用物理组装器创建结构";case "disassemble"->"用物理组装器拆回世界方块";default->"观察原生胶层和物理组装器";};}
    private String routine() {return parameters.operation().name().toLowerCase(Locale.ROOT);}
}
