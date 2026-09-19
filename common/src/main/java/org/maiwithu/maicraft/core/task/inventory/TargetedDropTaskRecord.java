// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.inventory;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** 在上层已选好的安全站位，将指定完整组件的精确数量投到接收格附近；自身不导航，也不创建消费日志。 */
public final class TargetedDropTaskRecord extends TaskRecord {
    static { TaskFactory.register(TargetedDropTaskRecord.class, TargetedDropCompanionTask::new); }
    public final int count;
    public final BlockPos receiver;
    private final ItemStack exactItem;

    public TargetedDropTaskRecord(String callId, long deadline, ItemStack exactItem, int count, BlockPos receiver) {
        super("targeted_drop", callId, deadline);
        if (exactItem == null || exactItem.isEmpty() || count < 1 || receiver == null)
            throw new IllegalArgumentException("targeted drop requires a nonempty exact item, positive count and receiver");
        this.exactItem = exactItem.copyWithCount(1); this.count = count; this.receiver = receiver.immutable();
    }

    // 调用方修改原物品或返回值不能改变已冻结的投料身份。
    public ItemStack exactItem() { return exactItem.copy(); }
    @Override public String describe() { return "向接收区精确投放 " + count + " 件 " + exactItem.getHoverName().getString(); }
}
