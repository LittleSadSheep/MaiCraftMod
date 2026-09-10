// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.physics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * 读取结构局部的真实碰撞面，找直立身体可能站得下的位置，并把候选转换到世界坐标。最多读八千一百九十二格、保留六十四个表面，不把候选当作已登船。
 */
public final class StructureDeckGeometry {
    private static final int RADIUS = 8, READ_BUDGET = 8192, SURFACE_BUDGET = 64;
    public record Surface(BlockState state, BlockPos block, AABB box, Vec3 storage, Vec3 world, Vec3 normal, Vec3 feet) {
        public Surface { block = block.immutable(); }
    }
    public record Sample(java.util.List<Surface> surfaces, int reads, int unknown, int shapeErrors,
                         int steep, boolean truncated, boolean exhausted) {
        public Sample { surfaces = java.util.List.copyOf(surfaces); }
        public String state() { return exhausted || unknown > 0 || shapeErrors > 0 || truncated ? "partial" : "sampled"; }
    }
    private StructureDeckGeometry() {}

    // 从关注位置向外扩八格，竖直方向先看近处再看更高或更低层；这样高塔顶部不会先耗尽下层甲板的观察预算。
    public static Sample sample(BlockGetter world, Predicate<BlockPos> loaded, StructurePose pose,
                             AABB storageBounds, BlockPos origin, Vec3 focus, double width, double height) {
        if (pose == null || storageBounds == null || origin == null)
            return new Sample(java.util.List.of(),0,1,0,0,false,false);
        GuardedView view = new GuardedView(world, loaded, storageBounds);
        Vec3 normal = pose.normalToWorld(new Vec3(0, 1, 0));
        var surfaces = new ArrayList<Surface>();
        int skippedSlopes = 0, shapeErrors = 0;
        int cx = (int)Math.floor(Math.clamp(focus.x, storageBounds.minX, storageBounds.maxX - 1));
        int cz = (int)Math.floor(Math.clamp(focus.z, storageBounds.minZ, storageBounds.maxZ - 1));
        int low = Math.max(world.getMinBuildHeight(), (int)Math.floor(storageBounds.minY));
        int high = Math.min(world.getMinBuildHeight() + world.getHeight() - 1, (int)Math.ceil(storageBounds.maxY) - 1);
        int cy = (int)Math.floor(Math.clamp(focus.y, low, Math.max(low, high)));
        int verticalRadius = Math.max(cy - low, high - cy);
        boolean truncated = false;
        search: for (int ring = 0; ring <= RADIUS; ring++) for (int dx = -ring; dx <= ring; dx++) for (int dz = -ring; dz <= ring; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
            int x = cx + dx, z = cz + dz;
            if (x < storageBounds.minX || x >= storageBounds.maxX || z < storageBounds.minZ || z >= storageBounds.maxZ) continue;
            // Spend the finite surface budget near the gaze, including its height. Scanning
            // from the roof would discard a viewed lower deck on a tall multi-floor vessel.
            for (int yi = 0; yi <= verticalRadius * 2; yi++) {
                int y = cy + (yi % 2 == 0 ? -yi / 2 : (yi + 1) / 2);
                if (y < low || y > high) continue;
                if (view.limited || surfaces.size() >= SURFACE_BUDGET) { truncated = true; break search; }
                BlockPos block = new BlockPos(x, y, z);
                BlockState state = view.getBlockState(block);
                if (state.isAir() || !state.getFluidState().isEmpty() || hazard(state)) continue;
                if (normal.y <= .6) { skippedSlopes++; continue; }
                try {
                    var boxes = state.getCollisionShape(view, block, CollisionContext.empty()).toAabbs();
                    if (boxes.size() > 64) { shapeErrors++; continue; }
                    for (AABB box : boxes) {
                        Surface candidate = inspect(view, pose, block, state, box, width, height);
                        if (candidate != null) surfaces.add(candidate);
                        if (surfaces.size() >= SURFACE_BUDGET) break;
                    }
                } catch (RuntimeException | LinkageError unknownShape) { shapeErrors++; }
            }
        }
        surfaces.sort(Comparator.comparingDouble(surface -> surface.storage().distanceToSqr(focus)));
        return new Sample(surfaces, view.reads, view.unknown, shapeErrors, skippedSlopes, truncated, view.limited);
    }

    /** Recheck a retained local face against the new pose and current native voxel/body geometry. */
    public static Surface probe(BlockGetter world, Predicate<BlockPos> loaded, StructurePose pose,
                                AABB bounds, Surface previous, double width, double height) {
        GuardedView view = new GuardedView(world, loaded, bounds);
        BlockState state = view.getBlockState(previous.block());
        if (!state.equals(previous.state()) || !state.getCollisionShape(view, previous.block(), CollisionContext.empty())
                .toAabbs().contains(previous.box()) || view.unknown > 0) return null;
        return inspect(view, pose, previous.block(), state, previous.box(), width, height);
    }

    private static Surface inspect(GuardedView view, StructurePose pose, BlockPos block, BlockState state,
                                   AABB box, double width, double height) {
        if (hazard(state) || !state.getFluidState().isEmpty()) return null;
        Vec3 normal = pose.normalToWorld(new Vec3(0,1,0));
        if (normal.y <= .6) return null;
        Vec3 surface = new Vec3(block.getX() + (box.minX + box.maxX) / 2, block.getY() + box.maxY,
                block.getZ() + (box.minZ + box.maxZ) / 2);
        Vec3 world = pose.toWorld(surface);
        double lift = width / 2 * (Math.abs(normal.x) + Math.abs(normal.z)) / normal.y + .002;
        Vec3 feet = world.add(0,lift,0);
        AABB body = new AABB(feet.x-width/2,feet.y,feet.z-width/2,feet.x+width/2,feet.y+height,feet.z+width/2);
        return clearBody(view, PhysicalObstacleSnapshot.transformBox(pose,body,false))
                ? new Surface(state,block,box,surface,world,normal,feet) : null;
    }

    // 多读一圈邻格是为了检查伸过来的碰撞；当前液体和危险物也按这整圈直接拒绝，范围超过了身体实际接触处。
    private static boolean clearBody(GuardedView view, AABB body) {
        if (!Double.isFinite(body.minX + body.minY + body.minZ + body.maxX + body.maxY + body.maxZ)
                || Math.min(body.minX, Math.min(body.minY, body.minZ)) < Integer.MIN_VALUE + 2D
                || Math.max(body.maxX, Math.max(body.maxY, body.maxZ)) > Integer.MAX_VALUE - 2D) {
            view.unknown++; return false;
        }
        int unknown = view.unknown;
        // Include neighboring origins for shapes which protrude beyond their own block cell.
        for (int x = (int)Math.floor(body.minX) - 1; x <= (int)Math.floor(body.maxX) + 1; x++)
            for (int y = (int)Math.floor(body.minY) - 1; y <= (int)Math.floor(body.maxY) + 1; y++)
                for (int z = (int)Math.floor(body.minZ) - 1; z <= (int)Math.floor(body.maxZ) + 1; z++) {
                    if (view.limited) return false;
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState state = view.getBlockState(p);
                    if (!state.getFluidState().isEmpty() || hazard(state)) return false;
                    for (AABB collision : state.getCollisionShape(view, p, CollisionContext.empty()).toAabbs())
                        if (collision.move(p).intersects(body.deflate(1e-6))) return false;
                }
        return unknown == view.unknown && !view.limited;
    }

    private static boolean hazard(BlockState state) {
        return state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.LAVA);
    }

    // 结构范围外当作此结构没有方块；范围内未加载或读取失败则计入未知，并限制总读取量。
    private static final class GuardedView implements BlockGetter {
        final BlockGetter world; final Predicate<BlockPos> loaded; final AABB bounds;
        final Map<BlockPos, BlockState> known = new HashMap<>();
        int reads, unknown; boolean limited;
        GuardedView(BlockGetter world, Predicate<BlockPos> loaded, AABB bounds) {
            this.world = world; this.loaded = loaded; this.bounds = bounds;
        }
        public BlockState getBlockState(BlockPos p) {
            BlockState cached = known.get(p);
            if (cached != null) return cached;
            if (reads >= READ_BUDGET) { limited = true; unknown++; return Blocks.AIR.defaultBlockState(); }
            reads++;
            if (!bounds.contains(Vec3.atCenterOf(p))) {
                BlockState air = Blocks.AIR.defaultBlockState(); known.put(p.immutable(), air); return air;
            }
            if (!loaded.test(p)) { unknown++; return Blocks.AIR.defaultBlockState(); }
            try { BlockState value = java.util.Objects.requireNonNull(world.getBlockState(p)); known.put(p.immutable(), value); return value; }
            catch (RuntimeException | LinkageError unavailable) { unknown++; return Blocks.AIR.defaultBlockState(); }
        }
        public BlockEntity getBlockEntity(BlockPos p) {
            if (!bounds.contains(Vec3.atCenterOf(p))) return null;
            if (!loaded.test(p)) { unknown++; return null; }
            return world.getBlockEntity(p);
        }
        public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }
        public int getHeight() { return world.getHeight(); }
        public int getMinBuildHeight() { return world.getMinBuildHeight(); }
    }
}
