// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 保存机器应至少装有多少件指定物品；数量范围为一至三万二千七百六十八。执行时保留原库存，只补不足部分。
 */
public final class MachineContentsTaskRecord extends TaskRecord {
    static { TaskFactory.register(MachineContentsTaskRecord.class, MachineContentsTask::new); }
    public final BlockPos target;
    public final ResourceLocation itemId;
    public final int count;
    public MachineContentsTaskRecord(String callId, long deadline, BlockPos target, String itemId, int count) {
        super("machine_initialize_contents", callId, deadline);
        this.target = Objects.requireNonNull(target).immutable();
        this.itemId = ResourceLocation.parse(itemId);
        if (count < 1 || count > 32768) throw new IllegalArgumentException("machine contents count must be 1..32768");
        this.count = count;
    }
    @Override public String describe() { return "ensure machine contains " + count + " " + itemId; }
}
