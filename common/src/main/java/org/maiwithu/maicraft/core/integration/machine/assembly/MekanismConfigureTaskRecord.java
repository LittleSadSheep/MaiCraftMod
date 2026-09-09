// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 保存一次机器接口配置要求：位置、哪一面、处理物品还是流体等介质、最后应是什么模式；实际支持范围在执行时读取。
 */
public final class MekanismConfigureTaskRecord extends TaskRecord {
    static { TaskFactory.register(MekanismConfigureTaskRecord.class, MekanismConfigureTask::new); }
    public final BlockPos target;
    public final Direction face;
    public final String medium, desiredMode;
    public MekanismConfigureTaskRecord(String callId, long deadline, BlockPos target,
            Direction face, String medium, String desiredMode) {
        super("machine_configure_mekanism", callId, deadline);
        this.target = Objects.requireNonNull(target).immutable();
        this.face = Objects.requireNonNull(face);
        this.medium = Objects.requireNonNull(medium);
        this.desiredMode = Objects.requireNonNull(desiredMode);
    }
    @Override public String describe() { return "configure Mekanism " + medium + " " + desiredMode; }
}
