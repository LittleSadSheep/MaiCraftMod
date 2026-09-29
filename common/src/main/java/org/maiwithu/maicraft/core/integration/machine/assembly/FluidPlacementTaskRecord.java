// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.integration.machine.MachinePlacementItems;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import java.util.stream.Collectors;

/** 固定本次倒桶目标与机器占位；源格只描述桶放在哪里，不限制放下后的流动范围。 */
public final class FluidPlacementTaskRecord extends TaskRecord {
    static { TaskFactory.register(FluidPlacementTaskRecord.class, FluidPlacementTask::new); }
    public final BlockPos target;
    public final BlockState expected;
    public final BucketItem bucket;
    public final Set<BlockPos> installation;
    public final BlockState removedSource;
    public FluidPlacementTaskRecord(String callId, long deadline, BlockPos target, BlockState expected,
                                    Set<BlockPos> installation) {
        this(callId,deadline,target,expected,installation,null);
    }
    // 修改机器明确要求该格为空气时，用同一套原生桶流程收回真实源格；流水不能冒充可取的一桶。
    public static FluidPlacementTaskRecord removeSource(String callId, long deadline, BlockPos target,
            BlockState source, Set<BlockPos> installation) {
        if (!(source.getBlock() instanceof LiquidBlock) || !source.getFluidState().isSource()
                || !(source.getFluidState().getType().getBucket() instanceof BucketItem))
            throw new IllegalArgumentException("fluid_removal_requires_bucket_source");
        return new FluidPlacementTaskRecord(callId,deadline,target,Blocks.AIR.defaultBlockState(),installation,source);
    }
    private FluidPlacementTaskRecord(String callId, long deadline, BlockPos target, BlockState expected,
            Set<BlockPos> installation, BlockState removedSource) {
        super("machine_place_source_fluid", callId, deadline);
        this.target = target.immutable(); this.expected = expected; this.removedSource = removedSource;
        bucket = removedSource == null ? MachinePlacementItems.fluidBucket(expected) : (BucketItem) Items.BUCKET;
        this.installation = installation.stream().map(BlockPos::immutable).collect(Collectors.toUnmodifiableSet());
        // 桶仍只能放到本次机器蓝图指定的格子，周围水流不必逐格声明成水源。
        if (!this.installation.contains(this.target)) throw new IllegalArgumentException("source fluid target is outside the installation");
    }
    @Override public String describe() { return removedSource == null ? "用真实桶填充声明的源流体格" : "用空桶回收声明拆除的源流体"; }
}
