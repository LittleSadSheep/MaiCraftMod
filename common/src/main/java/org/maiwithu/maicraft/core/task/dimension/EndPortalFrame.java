// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;

/** A complete, inward-facing ring of twelve existing frames. End frames are never manufactured. */
public record EndPortalFrame(BlockPos center) {
    public EndPortalFrame { center = center.immutable(); }

    public Map<BlockPos, Direction> frames() {
        Map<BlockPos, Direction> frames = new LinkedHashMap<>();
        for (var outward : Direction.Plane.HORIZONTAL) {
            for (int offset = -1; offset <= 1; offset++)
                frames.put(center.relative(outward, 2).relative(outward.getClockWise(), offset), outward.getOpposite());
        }
        return java.util.Collections.unmodifiableMap(frames);
    }

    public List<BlockPos> interior() {
        List<BlockPos> cells = new ArrayList<>();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) cells.add(center.offset(x, 0, z));
        return List.copyOf(cells);
    }

    public boolean intact(Function<BlockPos, BlockState> read) {
        return frames().entrySet().stream().allMatch(entry -> {
            BlockState state = read.apply(entry.getKey());
            return state != null && state.is(Blocks.END_PORTAL_FRAME)
                    && state.getValue(EndPortalFrameBlock.FACING) == entry.getValue();
        });
    }

    public List<BlockPos> missingEyes(Function<BlockPos, BlockState> read) {
        if (!intact(read)) throw new IllegalStateException("the End portal frame is incomplete or unloaded");
        return frames().keySet().stream().filter(p -> !read.apply(p).getValue(EndPortalFrameBlock.HAS_EYE)).toList();
    }

    /** Activation replaces all nine interior cells, so foreign blocks and unknown cells block preparation. */
    public boolean clearInterior(Function<BlockPos, BlockState> read) {
        return interior().stream().allMatch(p -> {
            BlockState state = read.apply(p);
            return state != null && (state.isAir() || state.is(Blocks.END_PORTAL));
        });
    }

    public boolean active(Function<BlockPos, BlockState> read) {
        return interior().stream().allMatch(p -> {
            BlockState state = read.apply(p);
            return state != null && state.is(Blocks.END_PORTAL);
        });
    }

    public static EndPortalFrame observe(Function<BlockPos, BlockState> read, BlockPos seed) {
        BlockState state = read.apply(seed);
        if (state == null || !state.is(Blocks.END_PORTAL_FRAME)) return null;
        Direction inward = state.getValue(EndPortalFrameBlock.FACING);
        for (int offset = -1; offset <= 1; offset++) {
            var ring = new EndPortalFrame(seed.relative(inward, 2).relative(inward.getClockWise(), offset));
            if (ring.intact(read)) return ring;
        }
        return null;
    }
}
