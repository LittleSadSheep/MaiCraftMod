package org.maiwithu.maicraft.core.integration.create.elevator;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * 按轿厢方块的实际碰撞找支撑面、厢内走路路径和进出连接。规划可暂按门打开后计算，执行仍核对真实门与地板。
 * 当前世界通道检查主要看碰撞，未检查身体所在格的火焰等无碰撞危险物。
 */
final class ElevatorGeometry {
    private static final double EPS = 1.0E-5;
    final List<AABB> actual;
    final List<AABB> doorsOpen;
    final List<Vec3> stances;
    final double width;
    final double height;

    record Landing(Vec3 outside, Vec3 inside) {}

    ElevatorGeometry(Map<BlockPos, StructureBlockInfo> blocks, BlockGetter view, double width, double height) {
        this.width = width; this.height = height;
        List<AABB> actual = new ArrayList<>(), opened = new ArrayList<>();
        var candidates = new LinkedHashSet<Vec3>();
        for (var entry : blocks.entrySet()) {
            BlockState state = entry.getValue().state();
            addBoxes(actual, state, view, entry.getKey());
            if ((state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock)
                    && state.hasProperty(BlockStateProperties.OPEN)) state = state.setValue(BlockStateProperties.OPEN, true);
            int first = opened.size();
            addBoxes(opened, state, view, entry.getKey());
            double x = entry.getKey().getX() + 0.5, z = entry.getKey().getZ() + 0.5;
            for (int i = first; i < opened.size(); i++) {
                AABB box = opened.get(i);
                if (box.minX <= x && box.maxX >= x && box.minZ <= z && box.maxZ >= z) candidates.add(new Vec3(x, box.maxY, z));
            }
        }
        this.actual = List.copyOf(actual); doorsOpen = List.copyOf(opened);
        var standing = new LinkedHashSet<Vec3>();
        for (Vec3 feet : candidates) {
            if (clear(body(feet), opened)) standing.add(feet);
            if (standing.size() >= 256) break;
        }
        stances = List.copyOf(standing);
    }

    AABB body(Vec3 feet) { return new AABB(feet.x - width / 2, feet.y + EPS, feet.z - width / 2,
            feet.x + width / 2, feet.y + height - EPS, feet.z + width / 2); }

    boolean carries(Vec3 localFeet) { return supported(localFeet, actual, width); }

    List<Landing> landings(BlockGetter world, Predicate<BlockPos> loaded, Vec3 origin, double step, LongSet forbidden) {
        return landings(world, loaded, origin, step, forbidden, Double.NaN);
    }

    List<Landing> landings(BlockGetter world, Predicate<BlockPos> loaded, Vec3 origin, double step, LongSet forbidden, double deck) {
        List<Landing> result = new ArrayList<>();
        for (Vec3 inside : stances) {
            if (Double.isFinite(deck) && Math.abs(inside.y - deck) > step) continue;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                Vec3 adjacent = inside.add(side.getStepX(), 0, side.getStepZ());
                if (supported(adjacent, doorsOpen, width)) continue;
                Vec3 outside = staticStance(world, loaded, adjacent.add(origin), width, height, step);
                if (outside == null || forbidden(outside, width, height, forbidden)) continue;
                Vec3 localOutside = outside.subtract(origin);
                if (Math.abs(localOutside.y - inside.y) > step + EPS || !clearStep(localOutside, inside, doorsOpen)) continue;
                if (!worldClear(world, loaded, body(outside)) || !worldClear(world, loaded, body(inside.add(origin)))) continue;
                double high = Math.max(outside.y, inside.y + origin.y);
                AABB crossing = body(new Vec3(outside.x, high, outside.z)).minmax(body(new Vec3(inside.x + origin.x, high, inside.z + origin.z)));
                if (!worldClear(world, loaded, crossing)) continue;
                result.add(new Landing(outside, inside));
                if (result.size() == 64) return List.copyOf(result);
            }
        }
        return List.copyOf(result);
    }

    List<Vec3> path(Vec3 from, Vec3 destination, Vec3 origin, double step, LongSet forbidden) {
        return pathTo(from, p -> p.distanceToSqr(destination) < 0.01, origin, step, forbidden);
    }

    List<Vec3> pathTo(Vec3 from, Predicate<Vec3> destination, Vec3 origin, double step, LongSet forbidden) {
        int count = stances.size();
        int[] previous = new int[count]; Arrays.fill(previous, -2);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < count; i++) {
            Vec3 p = stances.get(i);
            if (nearStep(from, p, step) && clearStep(from, p, doorsOpen)
                    && !forbidden(p.add(origin), width, height, forbidden)) { previous[i] = -1; queue.add(i); }
        }
        while (!queue.isEmpty()) {
            int index = queue.removeFirst(); Vec3 p = stances.get(index);
            if (destination.test(p)) {
                ArrayDeque<Vec3> result = new ArrayDeque<>();
                for (int at = index; at >= 0; at = previous[at]) result.addFirst(stances.get(at));
                return List.copyOf(result);
            }
            for (int i = 0; i < count; i++) {
                Vec3 next = stances.get(i);
                if (previous[i] != -2 || !nearStep(p, next, step)
                        || forbidden(next.add(origin), width, height, forbidden) || !clearStep(p, next, doorsOpen)) continue;
                previous[i] = index; queue.addLast(i);
            }
        }
        return List.of();
    }

    /** Prefer the connected cabin interior while keeping both boarding and arrival paths valid. */
    // 控制位置要同时能从入口走到、到目的层后能走向出口；在可行位置里偏好甲板中间。
    List<Vec3> interiorPath(Vec3 from, Vec3 entrance, Vec3 exit, Predicate<Vec3> usable,
                            Vec3 sourceOrigin, Vec3 targetOrigin, double deck, double step, LongSet forbidden) {
        if (forbidden(exit.add(targetOrigin), width, height, forbidden)) return List.of();
        WalkTree source = cabinWalk(from, sourceOrigin, step, forbidden);
        WalkTree arrival = cabinWalk(exit, targetOrigin, step, forbidden);
        Vec3 center = Vec3.ZERO; int count = 0;
        for (int i = 0; i < stances.size(); i++) {
            if (source.previous[i] == -2 || arrival.previous[i] == -2 || Math.abs(stances.get(i).y - deck) >= EPS) continue;
            center = center.add(stances.get(i)); count++;
        }
        center = count == 0 ? from : center.scale(1.0 / count);
        int best = -1; double bestScore = Double.MAX_VALUE; boolean bestOnDeck = false;
        for (int i = 0; i < stances.size(); i++) {
            Vec3 p = stances.get(i);
            if (source.previous[i] == -2 || arrival.previous[i] == -2 || Math.abs(p.y - deck) > step + EPS || !usable.test(p)) continue;
            boolean onDeck = Math.abs(p.y - deck) < EPS;
            // Center dominates; equally central positions favor space away from either threshold.
            double threshold = Math.min(p.distanceTo(entrance), p.distanceTo(exit));
            double score = p.distanceToSqr(center) - 0.05 * threshold + 0.001 * source.length[i];
            if (best < 0 || onDeck && !bestOnDeck || onDeck == bestOnDeck && score < bestScore) {
                best = i; bestScore = score; bestOnDeck = onDeck;
            }
        }
        if (best < 0) return List.of();
        ArrayDeque<Vec3> result = new ArrayDeque<>();
        for (int at = best; at >= 0; at = source.previous[at]) result.addFirst(stances.get(at));
        return List.copyOf(result);
    }

    private record WalkTree(int[] previous, int[] length) {}

    private WalkTree cabinWalk(Vec3 from, Vec3 origin, double step, LongSet forbidden) {
        int count = stances.size();
        int[] previous = new int[count], length = new int[count]; Arrays.fill(previous, -2);
        boolean[] allowed = new boolean[count];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < count; i++) {
            Vec3 p = stances.get(i);
            allowed[i] = !forbidden(p.add(origin), width, height, forbidden);
            if (allowed[i] && nearStep(from, p, step) && clearStep(from, p, doorsOpen)) {
                previous[i] = -1; queue.add(i);
            }
        }
        while (!queue.isEmpty()) {
            int index = queue.removeFirst(); Vec3 p = stances.get(index);
            for (int i = 0; i < count; i++) {
                Vec3 next = stances.get(i);
                if (!allowed[i] || previous[i] != -2 || !nearStep(p, next, step) || !clearStep(p, next, doorsOpen)) continue;
                previous[i] = index; length[i] = length[index] + 1; queue.addLast(i);
            }
        }
        return new WalkTree(previous, length);
    }

    boolean canStep(BlockGetter world, Predicate<BlockPos> loaded, Vec3 origin, Vec3 from, Vec3 to, double step, LongSet forbidden) {
        if (!nearStep(from, to, step) || forbidden(to, width, height, forbidden)) return false;
        if (!clearStep(from.subtract(origin), to.subtract(origin), actual)) return false;
        double length = Math.sqrt(from.distanceToSqr(to));
        int samples = Math.max(1, (int) Math.ceil(length / 0.15));
        for (int i = 1; i <= samples; i++) {
            Vec3 point = from.lerp(to, (double) i / samples);
            // Auto-step raises the feet before crossing the riser, matching normal walking.
            point = new Vec3(point.x, Math.max(from.y, to.y), point.z);
            if (!worldClear(world, loaded, body(point)) || forbidden(point, width, height, forbidden)) return false;
            if (!supported(point.subtract(origin), actual, width)
                    && staticStance(world, loaded, point, width, height, step) == null) return false;
        }
        return true;
    }

    private boolean clearStep(Vec3 from, Vec3 to, List<AABB> boxes) {
        double high = Math.max(from.y, to.y);
        Vec3 a = new Vec3(from.x, high, from.z), b = new Vec3(to.x, high, to.z);
        return clearSweep(from, a, boxes) && clearSweep(a, b, boxes) && clearSweep(b, to, boxes);
    }

    private boolean clearSweep(Vec3 from, Vec3 to, List<AABB> boxes) {
        for (AABB box : boxes) {
            AABB expanded = new AABB(box.minX - width / 2 + EPS, box.minY - height + EPS, box.minZ - width / 2 + EPS,
                    box.maxX + width / 2 - EPS, box.maxY - EPS, box.maxZ + width / 2 - EPS);
            if (expanded.contains(from) || expanded.contains(to) || expanded.clip(from, to).isPresent()) return false;
        }
        return true;
    }

    static boolean nearStep(Vec3 from, Vec3 to, double step) {
        return Math.abs(from.y - to.y) <= step + EPS
                && (from.x - to.x) * (from.x - to.x) + (from.z - to.z) * (from.z - to.z) <= 1.6;
    }

    static Vec3 staticStance(BlockGetter world, Predicate<BlockPos> loaded, Vec3 near, double width, double height, double step) {
        Vec3 best = null; double delta = Double.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = Mth.floor(near.y) - 2; y <= Mth.floor(near.y); y++) {
            pos.set(Mth.floor(near.x), y, Mth.floor(near.z));
            if (!loaded.test(pos)) continue;
            BlockState state = world.getBlockState(pos);
            if (!state.getFluidState().isEmpty() || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)) continue;
            for (AABB box : state.getCollisionShape(world, pos, CollisionContext.empty()).toAabbs()) {
                double top = y + box.maxY;
                Vec3 feet = new Vec3(near.x, top, near.z);
                if (Math.abs(top - near.y) > step + EPS || Math.abs(top - near.y) >= delta) continue;
                if (!new AABB(box.minX + pos.getX(), top - 0.01, box.minZ + pos.getZ(), box.maxX + pos.getX(), top + 0.01, box.maxZ + pos.getZ())
                        .intersects(feet.x - width / 2, top - 0.02, feet.z - width / 2, feet.x + width / 2, top + 0.02, feet.z + width / 2)) continue;
                AABB body = new AABB(feet.x - width / 2, top + EPS, feet.z - width / 2, feet.x + width / 2, top + height - EPS, feet.z + width / 2);
                if (worldClear(world, loaded, body)) { best = feet; delta = Math.abs(top - near.y); }
            }
        }
        return best;
    }

    // 为避免漏掉邻格伸入的形状，多查一圈碰撞来源；这里目前没有相应的火焰或液体接触检查。
    static boolean worldClear(BlockGetter world, Predicate<BlockPos> loaded, AABB body) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = Mth.floor(body.minX) - 1; x <= Mth.floor(body.maxX) + 1; x++) {
            for (int z = Mth.floor(body.minZ) - 1; z <= Mth.floor(body.maxZ) + 1; z++) {
                for (int y = Mth.floor(body.minY) - 1; y <= Mth.floor(body.maxY) + 1; y++) {
                    pos.set(x, y, z); if (!loaded.test(pos)) return false;
                    for (AABB box : world.getBlockState(pos).getCollisionShape(world, pos, CollisionContext.empty()).toAabbs()) {
                        if (body.intersects(box.move(pos))) return false;
                    }
                }
            }
        }
        return true;
    }

    static boolean forbidden(Vec3 feet, double width, double height, LongSet cells) {
        if (cells.isEmpty()) return false;
        for (int x = Mth.floor(feet.x - width / 2 + EPS); x <= Mth.floor(feet.x + width / 2 - EPS); x++)
            for (int z = Mth.floor(feet.z - width / 2 + EPS); z <= Mth.floor(feet.z + width / 2 - EPS); z++)
                for (int y = Mth.floor(feet.y + EPS); y <= Mth.floor(feet.y + height - EPS); y++)
                    if (cells.contains(BlockPos.asLong(x, y, z))) return true;
        return false;
    }

    static boolean supported(Vec3 feet, List<AABB> boxes, double width) {
        return boxes.stream().anyMatch(b -> Math.abs(b.maxY - feet.y) < 0.08
                && b.maxX > feet.x - width / 2 && b.minX < feet.x + width / 2
                && b.maxZ > feet.z - width / 2 && b.minZ < feet.z + width / 2);
    }
    private static boolean clear(AABB body, List<AABB> boxes) { return boxes.stream().noneMatch(body::intersects); }
    private static void addBoxes(List<AABB> out, BlockState state, BlockGetter world, BlockPos pos) {
        state.getCollisionShape(world, pos, CollisionContext.empty()).forAllBoxes((a,b,c,d,e,f) ->
                out.add(new AABB(a + pos.getX(), b + pos.getY(), c + pos.getZ(), d + pos.getX(), e + pos.getY(), f + pos.getZ())));
    }
}
