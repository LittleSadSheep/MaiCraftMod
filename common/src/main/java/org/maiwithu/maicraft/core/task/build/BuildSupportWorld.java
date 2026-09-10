// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** A bounded, read-only projection; assumptions never enter the live level or grant scaffold ownership. */
final class BuildSupportWorld implements BlockGetter {
    private static final int MAX_READS = 8192;
    private final BlockGetter live;
    private final Predicate<BlockPos> loaded;
    private final Map<BlockPos, BlockState> observed = new LinkedHashMap<>();
    private final Map<BlockPos, BlockState> proposed;
    private boolean sawUnloaded;

    BuildSupportWorld(BlockGetter live, Predicate<BlockPos> loaded, Map<BlockPos, BlockState> proposed) {
        this.live = live; this.loaded = loaded; this.proposed = Map.copyOf(proposed);
    }

    @Override public BlockState getBlockState(BlockPos pos) {
        if (!loaded.test(pos)) { sawUnloaded = true; return Blocks.BARRIER.defaultBlockState(); }
        BlockState actual = observed.get(pos);
        if (actual == null) {
            if (observed.size() >= MAX_READS) throw new IllegalStateException("support observation budget exceeded");
            actual = live.getBlockState(pos); observed.put(pos.immutable(), actual);
        }
        return proposed.getOrDefault(pos, actual);
    }

    boolean unchanged() {
        for (var entry : observed.entrySet())
            if (!loaded.test(entry.getKey()) || !entry.getValue().equals(live.getBlockState(entry.getKey()))) return false;
        return true;
    }

    boolean sawUnloaded() { return sawUnloaded; }
    boolean proposes(BlockPos pos) { return proposed.containsKey(pos); }
    int reads() { return observed.size(); }
    @Override public BlockEntity getBlockEntity(BlockPos pos) {
        return proposed.containsKey(pos) || !loaded.test(pos) ? null : live.getBlockEntity(pos);
    }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public int getHeight() { return live.getHeight(); }
    @Override public int getMinBuildHeight() { return live.getMinBuildHeight(); }
}
