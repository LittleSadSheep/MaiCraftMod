// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Read-only collision view at one point in construction, rather than the finished solid volume. */
final class BuildPlacementStage implements BlockGetter {
    private final BlockGetter world;
    private final Predicate<BlockPos> loaded;
    private final Map<Long, BuildTaskRecord.Target> targets;
    private final BuildTaskRecord.Target active;
    private final boolean preflight;

    BuildPlacementStage(BlockGetter world, Predicate<BlockPos> loaded,
                        Map<Long, BuildTaskRecord.Target> targets, BuildTaskRecord.Target active,
                        boolean preflight) {
        this.world = world; this.loaded = loaded; this.targets = targets;
        this.active = active; this.preflight = preflight;
    }

    BlockState state(BlockPos pos) {
        if (!loaded.test(pos)) return Blocks.BARRIER.defaultBlockState();
        BuildTaskRecord.Target planned = targets.get(pos.asLong());
        // Preflight can rely on explicitly earlier work, including excavation and support.
        // A future target retains its LIVE state; its unbuilt roof cannot occlude today's ray.
        // Execution always reads live blocks, even when prior work should already be complete.
        if (preflight && planned != null && BuildOrder.BUILD_ORDER.compare(planned, active) < 0)
            return planned.desiredState();
        return world.getBlockState(pos);
    }

    @Override public BlockState getBlockState(BlockPos pos) { return state(pos); }
    @Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
        return world.getBlockEntity(pos);
    }
    @Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
        return state(pos).getFluidState();
    }
    @Override public int getHeight() { return world.getHeight(); }
    @Override public int getMinBuildHeight() { return world.getMinBuildHeight(); }

    boolean support(BlockPos pos, net.minecraft.core.Direction face) {
        if (!loaded.test(pos)) return false;
        BlockState state = state(pos);
        if (state.isAir() || state.canBeReplaced()) return false;
        // A slab or stair is a legal click anchor even when the clicked face is not sturdy.
        // The item placement context separately checks any support needed for survival.
        return !state.getShape(this, pos).isEmpty();
    }

    boolean bodyCellAvailable(BlockPos feet) {
        if (feet.equals(active.pos()) || feet.above().equals(active.pos())) return false;
        BlockState low = state(feet), high = state(feet.above());
        if (!low.getCollisionShape(this, feet).isEmpty()
                || !high.getCollisionShape(this, feet.above()).isEmpty()
                || !low.getFluidState().isEmpty() || !high.getFluidState().isEmpty()) return false;
        // Missing floor remains a candidate only: the live navigator must provide real support.
        return state(feet.below()).getFluidState().isEmpty();
    }

    boolean rayClear(Vec3 from, Vec3 to, BlockPos clicked) {
        Vec3 delta = to.subtract(from);
        int samples = Math.max(2, (int) Math.ceil(delta.length() * 12));
        BlockPos previous = null;
        for (int i = 1; i < samples; i++) {
            BlockPos cell = BlockPos.containing(from.add(delta.scale(i / (double) samples)));
            if (cell.equals(clicked) || cell.equals(active.pos()) || cell.equals(previous)) continue;
            previous = cell;
            if (state(cell).getShape(this, cell).clip(from, to, cell) != null) return false;
        }
        return true;
    }
}
