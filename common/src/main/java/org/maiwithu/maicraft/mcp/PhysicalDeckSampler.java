// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.maiwithu.maicraft.core.integration.physics.StructurePose;

/** On-demand native voxel support samples. These are observations, never a flight/landing verdict. */
final class PhysicalDeckSampler {
    private static final int RADIUS = 8, READ_BUDGET = 8192, SURFACE_BUDGET = 64, RESULTS = 12;
    private record Surface(JsonObject data, double distance) {}
    private PhysicalDeckSampler() {}

    static JsonObject sample(BlockGetter world, Predicate<BlockPos> loaded, StructurePose pose,
                             AABB storageBounds, BlockPos origin, Vec3 focus, double width, double height) {
        if (pose == null || storageBounds == null || origin == null)
            return StructurePerceptionJson.state("unknown", "pose, plot bounds or local origin is unavailable");
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
                        Vec3 surface = new Vec3(x + (box.minX + box.maxX) / 2, y + box.maxY, z + (box.minZ + box.maxZ) / 2);
                        Vec3 worldSurface = pose.toWorld(surface);
                        // An upright body's uphill corner must clear a tilted face before considering it.
                        double lift = width / 2 * (Math.abs(normal.x) + Math.abs(normal.z)) / normal.y + .002;
                        Vec3 feet = worldSurface.add(0, lift, 0);
                        AABB body = new AABB(feet.x - width / 2, feet.y, feet.z - width / 2,
                                feet.x + width / 2, feet.y + height, feet.z + width / 2);
                        if (!clearBody(view, toStorageBox(pose, body))) continue;
                        JsonObject row = new JsonObject();
                        row.addProperty("block_id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                        row.add("block_storage", StructurePerceptionJson.vector(Vec3.atLowerCornerOf(block)));
                        row.add("block_local", StructurePerceptionJson.vector(Vec3.atLowerCornerOf(block.subtract(origin))));
                        row.add("surface_storage", StructurePerceptionJson.vector(surface));
                        row.add("surface_local", StructurePerceptionJson.vector(surface.subtract(Vec3.atLowerCornerOf(origin))));
                        row.add("surface_world", StructurePerceptionJson.vector(worldSurface));
                        row.add("normal_world", StructurePerceptionJson.vector(normal));
                        row.add("upright_feet_candidate", StructurePerceptionJson.vector(feet));
                        row.add("support_block_collision_box", StructurePerceptionJson.box(box));
                        row.addProperty("local_surface_area", (box.maxX - box.minX) * (box.maxZ - box.minZ));
                        row.addProperty("upright_structure_body_clear", true);
                        row.addProperty("standing_verified", false);
                        row.addProperty("route_verified", false);
                        surfaces.add(new Surface(row, surface.distanceToSqr(focus)));
                        if (surfaces.size() >= SURFACE_BUDGET) break;
                    }
                } catch (RuntimeException | LinkageError unknownShape) { shapeErrors++; }
            }
        }
        surfaces.sort(Comparator.comparingDouble(Surface::distance));
        JsonArray rows = new JsonArray();
        surfaces.stream().limit(RESULTS).forEach(s -> rows.add(s.data()));
        JsonObject out = new JsonObject();
        out.addProperty("state", view.limited || view.unknown > 0 || shapeErrors > 0 || truncated ? "partial" : "sampled");
        out.add("support_surface_candidates", rows);
        out.addProperty("read_only", true); out.addProperty("full_structure_observed", false);
        out.addProperty("column_radius", RADIUS); out.addProperty("block_reads", view.reads);
        out.addProperty("unknown_reads", view.unknown); out.addProperty("shape_errors", shapeErrors);
        out.addProperty("steep_surfaces_skipped", skippedSlopes);
        out.addProperty("results_truncated", truncated || surfaces.size() > RESULTS);
        out.addProperty("budget_exhausted", view.limited);
        out.addProperty("collision_evidence", "native block collision shapes; conservative inverse-transformed upright body box");
        out.addProperty("support_evidence", "world-Y upright candidate; Sable uses actual contact MTV dot entityUp > 0.6, so a surface normal alone does not prove landing");
        return out;
    }

    static AABB toStorageBox(StructurePose pose, AABB world) {
        Vec3 min = new Vec3(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        Vec3 max = new Vec3(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        for (int bits = 0; bits < 8; bits++) {
            Vec3 p = pose.toStorage(new Vec3((bits & 1) == 0 ? world.minX : world.maxX,
                    (bits & 2) == 0 ? world.minY : world.maxY, (bits & 4) == 0 ? world.minZ : world.maxZ));
            min = new Vec3(Math.min(min.x, p.x), Math.min(min.y, p.y), Math.min(min.z, p.z));
            max = new Vec3(Math.max(max.x, p.x), Math.max(max.y, p.y), Math.max(max.z, p.z));
        }
        return new AABB(min, max);
    }

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
