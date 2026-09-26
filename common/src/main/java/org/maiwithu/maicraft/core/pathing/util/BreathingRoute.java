package org.maiwithu.maicraft.core.pathing.util;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.create.ContraptionObstacles;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;

/** 缺氧时按实际身体通道搜索空气；保留每个转弯，禁止把透气口坐标直接当作穿墙上浮方向。 */
public final class BreathingRoute {
    public interface View {
        boolean clear(Vec3 from, Vec3 to);
        boolean breathable(Vec3 feet);
    }
    public record Route(List<Vec3> points, double ticks, boolean reachesAir) {
        public Route { points = List.copyOf(points); }
        public int requiredAir(double rate) { return (int) Math.min(Integer.MAX_VALUE, Math.ceil((ticks + 60) * Math.max(1, rate))); }
    }
    private record Node(BlockPos offset, double ticks) {}
    private BreathingRoute() {}

    /** 搜索按游泳时间展开，先比较绕行和爬升代价；在深海没有读到水面时仍可执行已验证的上浮前缀。 */
    public static final class Search {
        private final Vec3 origin;
        private final Vec3 start;
        private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::ticks));
        private final Map<BlockPos, Double> costs = new HashMap<>();
        private final Map<BlockPos, BlockPos> parents = new HashMap<>();
        private int expanded;
        private boolean done;
        private Route result;
        public Search(Vec3 start) {
            this.start = start; origin = new Vec3(Math.floor(start.x) + .5, start.y, Math.floor(start.z) + .5);
            open.add(new Node(BlockPos.ZERO, start.distanceTo(origin) / .10)); costs.put(BlockPos.ZERO, start.distanceTo(origin) / .10);
        }
        public boolean done() { return done; }
        public Route result() { return result; }
        public int expanded() { return expanded; }
        public void advance(View view, int budget) {
            if (!view.clear(start, origin)) { done = true; return; }
            long until = System.nanoTime() + 1_000_000L;
            for (int n = 0; !done && n < budget && (n == 0 || System.nanoTime() < until); n++) {
                if (open.isEmpty() || expanded >= 4096) { done = true; return; }
                Node node = open.remove();
                if (node.ticks != costs.get(node.offset)) continue;
                Vec3 from = point(node.offset);
                if (!view.clear(from, from)) continue;
                if (view.breathable(from)) { result = assemble(node, true); done = true; return; }
                expanded++;
                for (Direction direction : Direction.values()) {
                    BlockPos next = node.offset.relative(direction);
                    if (Math.abs(next.getX()) > 16 || Math.abs(next.getZ()) > 16 || Math.abs(next.getY()) > 16) continue;
                    double ticks = node.ticks + (direction == Direction.UP ? 1 / .12 : 1 / .10);
                    if (ticks >= costs.getOrDefault(next, Double.POSITIVE_INFINITY) || !view.clear(from, point(next))) continue;
                    costs.put(next, ticks); parents.put(next, node.offset); open.add(new Node(next, ticks));
                }
            }
        }
        private Vec3 point(BlockPos offset) { return origin.add(offset.getX(), offset.getY(), offset.getZ()); }
        private Route assemble(Node node, boolean air) {
            var path = new ArrayList<Vec3>();
            for (BlockPos at = node.offset; at != null; at = parents.get(at)) path.add(point(at));
            Collections.reverse(path); if (start.distanceToSqr(origin) > .001) path.addFirst(start);
            return new Route(path, node.ticks, air);
        }
    }

    // 开阔水柱才走快捷上浮；逐半格验证全身碰撞，含水半砖或低顶板会立即使快捷路径失效。
    public static Route ascent(View view, Vec3 origin) {
        Vec3 prior = origin;
        if (view.breathable(origin)) return new Route(List.of(origin), 0, true);
        for (int step = 1; step <= 32; step++) {
            Vec3 next = origin.add(0, step * .5, 0);
            if (!view.clear(prior, next)) return null;
            if (view.breathable(next)) return new Route(List.of(origin, next), step * .5 / .12, true);
            prior = next;
        }
        return new Route(List.of(origin, prior), 16 / .12, false);
    }

    public static View observed(LocalPlayer player, LongSet forbidden) {
        var world = player.clientLevel;
        var geometry = geometry(world, world::isLoaded, forbidden, player.getBbWidth(), Math.max(.6, player.getBbHeight()), player.getEyeHeight());
        var moving = ContraptionObstacles.capture(world, player.position());
        return new View() {
            public boolean clear(Vec3 from, Vec3 to) {
                if (!geometry.clear(from, to) || !moving.clearSegment(from, to, player.getBbWidth(), player.getBbHeight())) return false;
                AABB swept = body(from, player.getBbWidth(), player.getBbHeight()).minmax(body(to, player.getBbWidth(), player.getBbHeight()));
                return world.getWorldBorder().isWithinBounds(swept) && SableStructureBridge.clearBody(world, swept);
            }
            public boolean breathable(Vec3 feet) { return geometry.breathable(feet); }
        };
    }

    /** 身体在水中可以自由移动，但不能穿含水实体、未加载邻块、岩浆或被明确禁止进入的位置。 */
    public static View geometry(BlockGetter world, Predicate<BlockPos> loaded, LongSet forbidden, double width, double height, double eyes) {
        return new View() {
            public boolean clear(Vec3 from, Vec3 to) {
                // 搜索的每条边沿单轴移动，合并起止身体盒就是完整扫掠；少量斜向纠偏使用保守包围盒。
                    AABB body = body(from, width, height).minmax(body(to, width, height)), origins = body.inflate(1);
                    if (body.minY < world.getMinBuildHeight() || body.maxY >= world.getMaxBuildHeight()) return false;
                    for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(origins.minX, origins.minY, origins.minZ),
                            BlockPos.containing(origins.maxX, origins.maxY, origins.maxZ))) {
                        if (!loaded.test(pos)) return false;
                        var state = world.getBlockState(pos);
                        if (body.intersects(new AABB(pos)) && (forbidden.contains(pos.asLong()) || !state.getFluidState().isEmpty() && !water(state.getFluidState())
                                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.BUBBLE_COLUMN) || state.getBlock() instanceof BaseFireBlock)) return false;
                        for (AABB shape : state.getCollisionShape(world, pos).toAabbs()) if (body.intersects(shape.move(pos))) return false;
                    }
                return true;
            }
            public boolean breathable(Vec3 feet) {
                Vec3 eye = feet.add(0, eyes, 0); BlockPos pos = BlockPos.containing(eye);
                if (!loaded.test(pos)) return false;
                var fluid = world.getFluidState(pos);
                return fluid.isEmpty() || water(fluid) && eye.y > pos.getY() + fluid.getHeight(world, pos) + .02;
            }
        };
    }
    private static AABB body(Vec3 feet, double width, double height) {
        double half = width / 2;
        return new AABB(feet.x - half, feet.y + .001, feet.z - half, feet.x + half, feet.y + height - .001, feet.z + half);
    }
    private static boolean water(FluidState fluid) { return fluid.is(FluidTags.WATER) || fluid.is(Fluids.WATER) || fluid.is(Fluids.FLOWING_WATER); }
}
