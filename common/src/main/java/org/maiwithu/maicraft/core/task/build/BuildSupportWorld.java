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

/** 有界只读投影；任何假设都不会写入实时世界，也不会授予脚手架所有权。 */
final class BuildSupportWorld implements BlockGetter {
    private static final int MAX_READS = 8192;
    private final BlockGetter live;
    private final Predicate<BlockPos> loaded;
    private static final class Observation {
        final Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        boolean sawUnloaded;
    }
    private final Observation observation;
    private final Map<BlockPos, BlockState> proposed;

    BuildSupportWorld(BlockGetter live, Predicate<BlockPos> loaded, Map<BlockPos, BlockState> proposed) {
        this(live, loaded, proposed, new Observation());
    }
    private BuildSupportWorld(BlockGetter live, Predicate<BlockPos> loaded, Map<BlockPos, BlockState> proposed, Observation observation) {
        this.live = live; this.loaded = loaded; this.proposed = Map.copyOf(proposed); this.observation = observation;
    }
    BuildSupportWorld withProjection(Map<BlockPos, BlockState> prefix) {
        // 每步只看此前能放下的支撑前缀，所有步骤共用同一原始观察和读取上限，不能借后面的垫块证明第一块。
        return new BuildSupportWorld(live, loaded, prefix, observation);
    }

    @Override public BlockState getBlockState(BlockPos pos) {
        if (!loaded.test(pos)) { observation.sawUnloaded = true; return Blocks.BARRIER.defaultBlockState(); }
        BlockState actual = observation.states.get(pos);
        if (actual == null) {
            if (observation.states.size() >= MAX_READS) throw new IllegalStateException("support observation budget exceeded");
            actual = live.getBlockState(pos); observation.states.put(pos.immutable(), actual);
        }
        return proposed.getOrDefault(pos, actual);
    }

    boolean unchanged() {
        for (var entry : observation.states.entrySet())
            if (!loaded.test(entry.getKey()) || !entry.getValue().equals(live.getBlockState(entry.getKey()))) return false;
        return true;
    }

    boolean sawUnloaded() { return observation.sawUnloaded; }
    boolean proposes(BlockPos pos) { return proposed.containsKey(pos); }
    int reads() { return observation.states.size(); }
    @Override public BlockEntity getBlockEntity(BlockPos pos) {
        return proposed.containsKey(pos) || !loaded.test(pos) ? null : live.getBlockEntity(pos);
    }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public int getHeight() { return live.getHeight(); }
    @Override public int getMinBuildHeight() { return live.getMinBuildHeight(); }
}
