// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.integration.machine.MachinePlacementItems;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import java.util.stream.Collectors;

/** 固定一格源流体与同种流体的声明范围；桶物品由流体注册定义派生，不含专用水池或配方材料。 */
public final class FluidPlacementTaskRecord extends TaskRecord {
    static { TaskFactory.register(FluidPlacementTaskRecord.class, FluidPlacementTask::new); }
    public final BlockPos target;
    public final BlockState expected;
    public final BucketItem bucket;
    public final Set<BlockPos> sourceRegion;
    public final Set<BlockPos> installation;
    public FluidPlacementTaskRecord(String callId, long deadline, BlockPos target, BlockState expected,
                                    Set<BlockPos> sourceRegion, Set<BlockPos> installation) {
        super("machine_place_source_fluid", callId, deadline);
        this.target = target.immutable(); this.expected = expected; bucket = MachinePlacementItems.fluidBucket(expected);
        this.sourceRegion = sourceRegion.stream().map(BlockPos::immutable).collect(Collectors.toUnmodifiableSet());
        this.installation = installation.stream().map(BlockPos::immutable).collect(Collectors.toUnmodifiableSet());
        if (!this.sourceRegion.contains(this.target)) throw new IllegalArgumentException("source fluid target is outside its declared region");
        if (!this.installation.containsAll(this.sourceRegion)) throw new IllegalArgumentException("source fluid region is outside the installation");
    }
    @Override public String describe() { return "用真实桶填充声明的源流体格"; }
}
