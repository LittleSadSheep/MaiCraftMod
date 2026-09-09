// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 为放置检查提供一份只读的“施工到这一步时会是什么样”的世界视图。
 * 预检可以把较早的目标当作已完成；真正施工时一律读现场，避免把还没盖的屋顶当成已经挡住视线。
 */
final class BuildPlacementStage implements BlockGetter {
    // A small stability margin around actual outlines prevents aiming through zero-width shared edges.
    private static final double RAY_CLEARANCE = 1.0 / 64.0;
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
        // 没加载的格子当作屏障，不能据此推断那里有空位。
        if (!loaded.test(pos)) return Blocks.BARRIER.defaultBlockState();
        BuildTaskRecord.Target planned = targets.get(pos.asLong());
        // 只有预检且目标在当前格之前施工，才用计划状态；以后才建的格子仍保留现场状态。
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
        // 半砖和楼梯也能被右键点击，不必是完整承重面；新方块放下后能否存活还要另查。
        return !state.getShape(this, pos).isEmpty();
    }

    // 候选站位的脚和头不能占用当前目标，不能有碰撞或流体；这里还没有证明能实际走到这个位置。
    boolean bodyCellAvailable(BlockPos feet) {
        if (feet.equals(active.pos()) || feet.above().equals(active.pos())) return false;
        BlockState low = state(feet), high = state(feet.above());
        if (!low.getCollisionShape(this, feet).isEmpty()
                || !high.getCollisionShape(this, feet.above()).isEmpty()
                || !low.getFluidState().isEmpty() || !high.getFluidState().isEmpty()) return false;
        // 脚下即使没有地板也先保留为候选，后续导航必须找到真实支撑；脚下是液体则拒绝。
        return state(feet.below()).getFluidState().isEmpty();
    }

    // Enumerate the short ray's bounding cells: point sampling skips both walls at a shared corner.
    // Inflate the actual outline boxes, not the whole block, so slabs, stairs and panes retain real gaps.
    boolean rayClear(Vec3 from, Vec3 to, BlockPos clicked) {
        AABB bounds = new AABB(from, to).inflate(RAY_CLEARANCE);
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ),
                BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ))) {
            if (cell.equals(clicked) || cell.equals(active.pos())) continue;
            // Cull cells before reading shapes; all callers already cap the ray to interaction reach.
            if (!intersectsRay(new AABB(cell).inflate(RAY_CLEARANCE), from, to)) continue;
            for (AABB box : state(cell).getShape(this, cell).toAabbs())
                if (intersectsRay(box.move(cell).inflate(RAY_CLEARANCE), from, to)) return false;
        }
        return true;
    }

    private static boolean intersectsRay(AABB box, Vec3 from, Vec3 to) {
        return box.contains(from) || box.contains(to) || box.clip(from, to).isPresent();
    }
}
