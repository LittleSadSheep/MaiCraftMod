package org.maiwithu.maicraft.core.tools.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.maiwithu.maicraft.core.pathing.moves.MovementHelper;

/**
 * 粗看周围不同高度的可站表面、门、攀爬物和危险方向；保留实际碰撞高度与可见性，不把候选当成已经有路。
 * 当前现场入口使用的 hasChunkAt 在原版客户端恒为真，不能保证这里只读到已加载地形。
 */
public final class LocalFloorSense {
    private static final int RADIUS = 8, DEPTH = 16, RISE = 2, STRIDE = 2;
    private LocalFloorSense() {}

    public static JsonObject describe(LocalPlayer player) {
        return describe(player.level(), player.level()::hasChunkAt, player.position(),
                player.getBbWidth(), player.getBbHeight(), player.getEyeHeight());
    }

    static JsonObject describe(BlockGetter world, Predicate<BlockPos> loaded, Vec3 origin,
                               double width, double height, double eyeHeight) {
        View view = new View(world, loaded);
        Vec3 eyes = origin.add(0, eyeHeight, 0);
        Map<String, Sector> sectors = new LinkedHashMap<>();
        Map<Double, Floor> floors = new HashMap<>();
        Map<String, Integer> hazards = new LinkedHashMap<>();
        JsonArray features = new JsonArray();
        List<BlockPos> columns = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx += STRIDE)
            for (int dz = -RADIUS; dz <= RADIUS; dz += STRIDE) columns.add(new BlockPos(dx, 0, dz));
        columns.sort(Comparator.comparingDouble(p -> p.getX() * p.getX() + p.getZ() * p.getZ()));
        int bottom = Math.max(world.getMinBuildHeight(), (int) Math.floor(origin.y) - DEPTH - 1);
        int top = Math.min(world.getMaxBuildHeight() - 1, (int) Math.floor(origin.y) + RISE - 1);
        for (BlockPos offset : columns) {
            double x = origin.x + offset.getX(), z = origin.z + offset.getZ();
            Sector sector = sectors.computeIfAbsent(sector(offset.getX(), offset.getZ()), ignored -> new Sector());
            sector.samples++;
            Floor near = null;
            double nearestLower = Double.NEGATIVE_INFINITY;
            for (int y = top; y >= bottom; y--) {
                BlockPos block = BlockPos.containing(x, y, z);
                BlockState state = view.getBlockState(block);
                if (features.size() < 8 && (state.getBlock() instanceof LadderBlock
                        || state.is(BlockTags.CLIMBABLE) || state.getBlock() instanceof DoorBlock)
                        && visible(view, eyes, Vec3.atCenterOf(block), block)) {
                    JsonObject feature = position(block.getX(), block.getY(), block.getZ(), origin.y);
                    feature.addProperty("kind", state.getBlock() instanceof DoorBlock ? "door" : "climbable");
                    feature.addProperty("route_verified", false);
                    features.add(feature);
                }
                if (MovementHelper.avoidWalkingInto(state)) {
                    if (Math.abs(y - origin.y) <= 1 && visible(view, eyes, Vec3.atCenterOf(block), block))
                        hazards.merge(state.getFluidState().isEmpty() ? "damaging_or_obstructing_block" : "fluid", 1, Integer::sum);
                    continue;
                }
                double standingY = supportTop(state, view, block, x, z, width);
                if (!Double.isFinite(standingY) || standingY < origin.y - DEPTH || standingY > origin.y + RISE) continue;
                if (!clearBody(view, x, standingY, z, width, height)) continue;
                boolean seen = visible(view, eyes, new Vec3(x, standingY + Math.min(eyeHeight, height / 2), z), null);
                Floor candidate = new Floor(x, standingY, z, seen,
                        offset.getX() * offset.getX() + offset.getZ() * offset.getZ());
                Floor old = floors.get(standingY);
                if (old == null || (seen && !old.visible())
                        || (seen == old.visible() && candidate.distance() < old.distance())) floors.put(standingY, candidate);
                if (standingY < origin.y) nearestLower = Math.max(nearestLower, standingY);
                if (Math.abs(standingY - origin.y) <= 1.01 && seen
                        && (near == null || Math.abs(standingY - origin.y) < Math.abs(near.y() - origin.y))) near = candidate;
            }
            if (near != null) sector.add(near.y());
            else if (clearBody(view, x, origin.y, z, width, height)
                    && visible(view, eyes, new Vec3(x, eyes.y, z), null)) {
                hazards.merge(Double.isFinite(nearestLower) ? "drop_or_lower_floor" : "support_below_not_observed", 1, Integer::sum);
            }
        }
        JsonArray regions = new JsonArray();
        sectors.forEach((name, sector) -> regions.add(sector.json(name)));
        List<Floor> ordered = new ArrayList<>(floors.values());
        ordered.sort(Comparator.comparingDouble(floor -> Math.abs(floor.y() - origin.y)));
        List<Floor> reported = new ArrayList<>(ordered.stream().limit(8).toList());
        // 楼梯的多个踏步高度可能占满前八个名额，所以额外保留最下层，让观察者仍能知道楼下存在。
        ordered.stream().min(Comparator.comparingDouble(Floor::y)).ifPresent(lowest -> {
            if (!reported.contains(lowest)) reported.add(lowest);
        });
        JsonArray candidates = new JsonArray();
        for (Floor floor : reported) {
            JsonObject item = position(floor.x(), floor.y(), floor.z(), origin.y);
            item.addProperty("geometry_standable", true);
            item.addProperty("visible", floor.visible());
            item.addProperty("evidence", floor.visible() ? "visible_geometry" : "loaded_geometry_only");
            item.addProperty("route_verified", false);
            candidates.add(item);
        }
        JsonArray risks = new JsonArray();
        hazards.forEach((kind, count) -> {
            JsonObject risk = new JsonObject(); risk.addProperty("kind", kind); risk.addProperty("samples", count); risks.add(risk);
        });
        JsonObject result = new JsonObject();
        result.addProperty("reference_feet_y", origin.y);
        result.addProperty("body_width", width);
        result.addProperty("body_height", height);
        result.addProperty("radius", RADIUS);
        result.addProperty("sample_stride", STRIDE);
        result.addProperty("scan_below", DEPTH);
        result.addProperty("scan_above", RISE);
        result.addProperty("partial", view.partial);
        result.addProperty("block_reads", view.cache.size());
        result.addProperty("route_evidence", "not_evaluated; standing geometry and visibility do not establish a continuous route");
        result.add("standable_regions", regions);
        result.add("floor_candidates", candidates);
        result.add("local_features", features);
        result.add("hazards", risks);
        return result;
    }

    // 按实际碰撞顶面确定站立高度，不把半砖和普通整块都当成同一高度。
    private static double supportTop(BlockState state, View view, BlockPos block, double x, double z, double width) {
        double top = Double.NEGATIVE_INFINITY;
        for (AABB shape : state.getCollisionShape(view, block).toAabbs()) {
            AABB box = shape.move(block);
            if (box.maxX > x - width / 2 && box.minX < x + width / 2
                    && box.maxZ > z - width / 2 && box.minZ < z + width / 2) top = Math.max(top, box.maxY);
        }
        return top;
    }

    // 检查身体占用格里的危险和碰撞。当前没有检查邻格伸进身体的形状，因此这里通过仍可能漏掉阻挡。
    private static boolean clearBody(View view, double x, double y, double z, double width, double height) {
        AABB body = new AABB(x - width / 2, y + 1.0E-5, z - width / 2,
                x + width / 2, y + height - 1.0E-5, z + width / 2);
        for (BlockPos block : BlockPos.betweenClosed(BlockPos.containing(body.minX, body.minY, body.minZ),
                BlockPos.containing(body.maxX - 1.0E-5, body.maxY, body.maxZ - 1.0E-5))) {
            BlockState state = view.getBlockState(block);
            if (MovementHelper.avoidWalkingInto(state)) return false;
            for (AABB shape : state.getCollisionShape(view, block).toAabbs())
                if (shape.move(block).intersects(body)) return false;
        }
        return true;
    }

    private static boolean visible(View view, Vec3 eyes, Vec3 target, BlockPos targetBlock) {
        var hit = view.clip(new ClipContext(eyes, target, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS || (targetBlock != null && hit.getBlockPos().equals(targetBlock));
    }

    private static JsonObject position(double x, double y, double z, double feetY) {
        JsonObject point = new JsonObject(); point.addProperty("x", x); point.addProperty("y", y); point.addProperty("z", z);
        JsonObject value = new JsonObject(); value.add("position", point); value.addProperty("relative_height", y - feetY);
        JsonObject cell = new JsonObject();
        cell.addProperty("x", (int) Math.floor(x)); cell.addProperty("y", (int) Math.floor(y)); cell.addProperty("z", (int) Math.floor(z));
        value.add("feet_cell", cell);
        return value;
    }

    private static String sector(int dx, int dz) {
        if (Math.abs(dx) <= 2 && Math.abs(dz) <= 2) return "center";
        if (Math.abs(dx) > Math.abs(dz)) return dx < 0 ? "west" : "east";
        return dz < 0 ? "north" : "south";
    }

    private record Floor(double x, double y, double z, boolean visible, double distance) {}

    private static final class Sector {
        int samples, visibleStanding;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        void add(double y) { visibleStanding++; minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
        JsonObject json(String name) {
            JsonObject value = new JsonObject(); value.addProperty("region", name);
            value.addProperty("standable_fraction", samples == 0 ? 0 : (double) visibleStanding / samples);
            if (visibleStanding > 0) { value.addProperty("min_y", minY); value.addProperty("max_y", maxY); }
            value.addProperty("evidence", "visible_geometry_near_current_floor");
            value.addProperty("route_verified", false);
            return value;
        }
    }

    // 缓存本次读过的格子；传入判断说未知、越过建筑高度或预算用尽时，按挡住处理并标为部分观察。
    private static final class View implements BlockGetter {
        final BlockGetter world;
        final Predicate<BlockPos> loaded;
        final Map<BlockPos, BlockState> cache = new HashMap<>();
        boolean partial;
        View(BlockGetter world, Predicate<BlockPos> loaded) { this.world = world; this.loaded = loaded; }
        public BlockState getBlockState(BlockPos pos) {
            BlockState cached = cache.get(pos);
            if (cached != null) return cached;
            if (pos.getY() < getMinBuildHeight() || pos.getY() >= getMaxBuildHeight()
                    || cache.size() >= 8192 || !loaded.test(pos)) {
                partial = true;
                return Blocks.BARRIER.defaultBlockState();
            }
            BlockState state = world.getBlockState(pos); cache.put(pos.immutable(), state); return state;
        }
        public BlockEntity getBlockEntity(BlockPos pos) { return loaded.test(pos) ? world.getBlockEntity(pos) : null; }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public int getHeight() { return world.getHeight(); }
        public int getMinBuildHeight() { return world.getMinBuildHeight(); }
    }
}
