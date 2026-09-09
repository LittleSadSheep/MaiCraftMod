// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 保存一台物流分拣机应只提取哪种物品；这是机器装配生成的要求，具体过滤列表和菜单操作由执行器处理。
 */
public final class MekanismFilterTaskRecord extends TaskRecord {
    static { TaskFactory.register(MekanismFilterTaskRecord.class, MekanismFilterTask::new); }
    public final BlockPos target;
    public final ResourceLocation itemId;
    public MekanismFilterTaskRecord(String callId, long deadline, BlockPos target, String itemId) {
        super("machine_configure_sorter_filter", callId, deadline);
        this.target = Objects.requireNonNull(target).immutable();
        this.itemId = ResourceLocation.parse(itemId);
    }
    @Override public String describe() { return "configure sorter to extract only " + itemId; }
}
