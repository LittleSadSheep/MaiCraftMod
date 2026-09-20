// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.inventory;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 在上层已选好的安全站位，将指定完整组件的精确数量投到接收格附近；自身不导航，也不创建消费日志。 */
public final class TargetedDropTaskRecord extends TaskRecord {
    static { TaskFactory.register(TargetedDropTaskRecord.class, TargetedDropCompanionTask::new); }
    public final int count;
    public final BlockPos receiver;
    public final TargetedDropRegion region;
    private final ItemStack exactItem;
    private final BooleanSupplier preThrowCheck;
    private final Supplier<TargetedDropRegion> aimRegionProvider;

    public TargetedDropTaskRecord(String callId, long deadline, ItemStack exactItem, int count, BlockPos receiver) {
        this(callId,deadline,exactItem,count,receiver,TargetedDropRegion.ofCells(Collections.singletonList(receiver)));
    }

    public TargetedDropTaskRecord(String callId, long deadline, ItemStack exactItem, int count, BlockPos receiver,
                                  Collection<BlockPos> cells) {
        this(callId,deadline,exactItem,count,receiver,TargetedDropRegion.ofCells(cells));
    }

    // 父任务先冻结实际接收格，再独立提供原料位置的瞄准偏好；任务单不能在运行中自行扩大硬接收范围。
    public TargetedDropTaskRecord(String callId, long deadline, ItemStack exactItem, int count, BlockPos receiver, TargetedDropRegion region) {
        this(callId,deadline,exactItem,count,receiver,region,()->true);
    }

    public TargetedDropTaskRecord(String callId, long deadline, ItemStack exactItem, int count, BlockPos receiver,
                                  TargetedDropRegion region, BooleanSupplier preThrowCheck) {
        this(callId,deadline,exactItem,count,receiver,region,()->region,preThrowCheck);
    }

    public TargetedDropTaskRecord(String callId, long deadline, ItemStack exactItem, int count, BlockPos receiver,
                                  TargetedDropRegion region, Supplier<TargetedDropRegion> aimRegionProvider,
                                  BooleanSupplier preThrowCheck) {
        super("targeted_drop", callId, deadline);
        if (exactItem == null || exactItem.isEmpty() || count < 1 || receiver == null || region == null || preThrowCheck == null || aimRegionProvider == null)
            throw new IllegalArgumentException("targeted drop requires a nonempty exact item, positive count and receiver");
        this.exactItem = exactItem.copyWithCount(1); this.count = count; this.receiver = receiver.immutable();
        this.region = region;
        this.preThrowCheck = preThrowCheck;
        this.aimRegionProvider = aimRegionProvider;
    }

    // 调用方修改原物品或返回值不能改变已冻结的投料身份。
    public ItemStack exactItem() { return exactItem.copy(); }
    // 开背包和转头期间已投原料仍会漂移；真正丢出触发物前由父任务用最新观察只读复核原生取物邻域。
    public boolean preThrowAllowed() { return preThrowCheck.getAsBoolean(); }
    public TargetedDropRegion aimRegion() { return Objects.requireNonNull(aimRegionProvider.get(),"targeted_drop_aim_region_missing"); }
    @Override public String describe() { return "向接收区精确投放 " + count + " 件 " + exactItem.getHoverName().getString(); }
}
