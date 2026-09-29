// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.stonecutter;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.core.task.base.NativeSubmissionTaskRecord;
import org.maiwithu.maicraft.task.TaskFactory;

/** 固定已有切石机、输入产物与有限次数；消费预约开始后不自动重复，未知结果保留人工核验。 */
public final class StonecuttingTaskRecord extends NativeSubmissionTaskRecord {
    public static final String TOOL_NAME = "stonecutting";
    public final ResourceLocation input, output;
    public final int count;
    public final BlockPos station;

    static { TaskFactory.register(StonecuttingTaskRecord.class, StonecuttingCompanionTask::new); }

    public StonecuttingTaskRecord(String callId, long deadline, StonecuttingParameters parameters, BlockPos station) {
        super(TOOL_NAME, callId, deadline, "stonecutting");
        if (station == null) throw new IllegalArgumentException("stonecutting requires an observed station position");
        this.input = parameters.input();
        this.output = parameters.output();
        this.count = parameters.count();
        this.station = station.immutable();
    }

    @Override public String describe() {
        return "在切石机把 " + count + " 个 " + input + " 切制为 " + output;
    }
}
