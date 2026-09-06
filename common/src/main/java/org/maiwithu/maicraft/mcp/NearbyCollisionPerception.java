package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;

/** Small on-demand geometry sample; no movement, chunk loading or route claims. */
final class NearbyCollisionPerception {
    static JsonObject observe(LocalPlayer player) {
        return observe(player.level(), player.level()::hasChunkAt, player.blockPosition(),
                player.getBoundingBox(), CollisionContext.of(player));
    }

    static JsonObject observe(BlockGetter world, Predicate<BlockPos> loaded, BlockPos center,
                              AABB body, CollisionContext context) {
        record Entry(JsonObject data, boolean overlaps, boolean protrudes, double distance) {}
        var entries = new ArrayList<Entry>();
        int missing = 0, errors = 0;
        for (BlockPos cursor : BlockPos.betweenClosed(center.offset(-2, -2, -2), center.offset(2, 2, 2))) {
            BlockPos pos = cursor.immutable();
            if (!loaded.test(pos)) { missing++; continue; }
            try {
                var state = world.getBlockState(pos);
                var shape = state.getCollisionShape(world, pos, context);
                if (shape.isEmpty()) continue;
                AABB local = shape.bounds();
                boolean protrudes = local.minX < 0 || local.minY < 0 || local.minZ < 0
                        || local.maxX > 1 || local.maxY > 1 || local.maxZ > 1;
                boolean overlaps = Shapes.joinIsNotEmpty(shape.move(pos.getX(), pos.getY(), pos.getZ()),
                        Shapes.create(body.deflate(1.0E-6)), BooleanOp.AND);
                JsonObject row = new JsonObject();
                row.addProperty("block_id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                JsonArray position = new JsonArray();
                position.add(pos.getX()); position.add(pos.getY()); position.add(pos.getZ());
                row.add("position", position);
                row.addProperty("block_state", state.toString());
                row.add("local_bounds", box(local));
                row.addProperty("extends_outside_cell", protrudes);
                row.addProperty("intersects_player", overlaps);
                row.addProperty("top_world_y", pos.getY() + local.maxY);
                var boxes = shape.toAabbs();
                JsonArray parts = new JsonArray();
                boxes.stream().limit(8).forEach(part -> parts.add(box(part)));
                row.add("local_boxes", parts);
                row.addProperty("boxes_truncated", boxes.size() > 8);
                entries.add(new Entry(row, overlaps, protrudes, pos.distSqr(center)));
            } catch (RuntimeException unsupportedShape) {
                errors++;
            }
        }
        entries.sort(Comparator.comparing(Entry::overlaps).reversed()
                .thenComparing(Comparator.comparing(Entry::protrudes).reversed())
                .thenComparingDouble(Entry::distance));
        JsonArray rows = new JsonArray();
        entries.stream().limit(20).forEach(entry -> rows.add(entry.data()));
        JsonObject result = new JsonObject();
        result.add("player_box", box(body));
        result.add("blocks", rows);
        result.addProperty("radius", 2);
        result.addProperty("sampled_cells", 125);
        result.addProperty("unloaded_cells", missing);
        result.addProperty("shape_errors", errors);
        result.addProperty("omitted_blocks", Math.max(0, entries.size() - rows.size()));
        result.addProperty("evidence", "native collision shapes in the loaded client world using the current player context; local boxes are offsets from each block position; this sample does not verify a route");
        return result;
    }

    private static JsonArray box(AABB bounds) {
        JsonArray values = new JsonArray();
        values.add(bounds.minX); values.add(bounds.minY); values.add(bounds.minZ);
        values.add(bounds.maxX); values.add(bounds.maxY); values.add(bounds.maxZ);
        return values;
    }
}
