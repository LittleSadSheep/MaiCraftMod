// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;

/** On-demand explanation of a failed JetpackRoute body sweep, using only the loaded local scene. */
public final class JetpackObstruction {
    private JetpackObstruction() {}

    public static Map<String, Object> inspect(LocalPlayerContext ctx, LongSet forbidden, Vec3 from, Vec3 to) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "unknown"); result.put("from", point(from)); result.put("to", point(to));
        result.put("scope", "loaded_client_world"); result.put("sample_limit", 400);
        try {
            ctx.requireCurrent();
            double distance = from.distanceTo(to);
            if (!Double.isFinite(distance) || distance > 79.8) {
                result.put("reason", "sample_budget_exceeded_or_nonfinite_segment"); return Map.copyOf(result);
            }
            int intervals = Math.max(1, (int) Math.ceil(distance / 0.2));
            if (intervals + 1 > 400) { result.put("reason", "sample_budget_exceeded"); return Map.copyOf(result); }
            var player = ctx.player(); var level = ctx.level();
            double half = player.getBbWidth() * 0.5 + 0.08;
            for (int index = 0; index <= intervals; index++) {
                Vec3 feet = from.lerp(to, (double) index / intervals);
                AABB box = new AABB(feet.x - half, feet.y + 0.001, feet.z - half,
                        feet.x + half, feet.y + player.getBbHeight() + 0.08, feet.z + half);
                AABB actualBody = player.getBoundingBox().move(feet.subtract(player.position()));
                result.put("sample_index", index); result.put("sample_point", point(feet)); result.put("planning_box", bounds(box));
                if (box.minY < level.getMinBuildHeight() || box.maxY >= level.getMaxBuildHeight()
                        || !level.getWorldBorder().isWithinBounds(box)) return found(result, "worldborder");
                BlockPos low = BlockPos.containing(box.minX, box.minY, box.minZ), high = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
                for (BlockPos pos : BlockPos.betweenClosed(low, high)) {
                    if (!level.hasChunkAt(pos)) { result.put("position", cell(pos)); return found(result, "unloaded"); }
                    BlockState state = level.getBlockState(pos);
                    String type = !state.getFluidState().isEmpty() ? "fluid" : hazard(state) ? "hazard" : forbidden.contains(pos.asLong()) ? "forbidden" : null;
                    if (type != null) { block(result, pos, state); return found(result, type); }
                }
                if (level.noCollision(player, box)) {
                    if (!org.maiwithu.maicraft.core.integration.physics.SableStructureBridge.clearBody(level, box)) {
                        result.put("reason", "sable_body_collision_or_native_query_unavailable");
                        return found(result, "physical_structure_check");
                    }
                    continue;
                }
                // Include neighbour-owned protruding shapes (fences and modded blocks), without loading chunks.
                for (BlockPos pos : BlockPos.betweenClosed(low.offset(-1, -1, -1), high.offset(1, 1, 1))) {
                    if (!level.hasChunkAt(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    var local = state.getCollisionShape(level, pos, CollisionContext.of(player));
                    var shape = local.move(pos.getX(), pos.getY(), pos.getZ());
                    if (!Shapes.joinIsNotEmpty(shape, Shapes.create(box), BooleanOp.AND)) continue;
                    block(result, pos, state);
                    result.put("local_shape_bounds", bounds(local.bounds()));
                    result.put("planning_margin_only", !Shapes.joinIsNotEmpty(shape, Shapes.create(actualBody), BooleanOp.AND));
                    return found(result, "block");
                }
                // The same entity predicate used by vanilla EntityGetter.getEntityCollisions.
                for (var entity : level.getEntities(player, box.inflate(1.0E-7), EntitySelector.NO_SPECTATORS.and(player::canCollideWith))) {
                    if (!entity.getBoundingBox().intersects(box)) continue;
                    result.put("entity_id", entity.getId()); result.put("entity_type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
                    result.put("position", point(entity.position())); result.put("entity_box", bounds(entity.getBoundingBox()));
                    result.put("planning_margin_only", !entity.getBoundingBox().intersects(actualBody));
                    return found(result, "entity");
                }
                result.put("reason", "native_noCollision_rejected_without_identified_local_collider");
                return found(result, "unknown");
            }
            result.put("reproduced", false); result.put("reason", "no obstruction reproduced in the current client scene");
        } catch (RuntimeException failure) {
            result.put("reason", "obstruction observation failed: " + failure.getClass().getSimpleName());
        }
        return Map.copyOf(result);
    }

    private static Map<String, Object> found(Map<String, Object> result, String type) {
        result.put("type", type); result.put("reproduced", true); return Map.copyOf(result);
    }
    private static void block(Map<String, Object> result, BlockPos pos, BlockState state) {
        result.put("position", cell(pos)); result.put("block_id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        result.put("block_state", state.toString());
    }
    private static boolean hazard(BlockState state) {
        return state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.POINTED_DRIPSTONE) || state.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.CampfireBlock;
    }
    private static Map<String, Integer> cell(BlockPos p) { return Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ()); }
    private static Map<String, Double> point(Vec3 p) { return Map.of("x", p.x, "y", p.y, "z", p.z); }
    private static List<Double> bounds(AABB b) { return List.of(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ); }
}
