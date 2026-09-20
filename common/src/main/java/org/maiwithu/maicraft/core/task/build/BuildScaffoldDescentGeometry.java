// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.baritone.GroundCorridor;
import org.maiwithu.maicraft.core.pathing.transport.TransportLanding;
import java.util.Collections;
import java.util.Comparator;

/** 只投影移除自有柱，证明一格竖直扫掠及柱底连片地面；不会写入原生世界。 */
final class BuildScaffoldDescentGeometry {
    private static final double EPS = 1e-5;
    private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private BuildScaffoldDescentGeometry() {}

    record Frame(View view, double width, double height, LongSet forbidden, PhysicalObstacleSnapshot physical) {
        GroundCorridor ground() { return new GroundCorridor(view, view.loaded, width, height, forbidden, physical); }
        boolean drop(Vec3 from, Vec3 to) {
            // 这里只接受同一水平位置、最多一格的自然下降；普通 GroundCorridor 仍只负责两端落脚。
            if (!finite(from) || !finite(to) || Math.abs(from.x - to.x) > EPS || Math.abs(from.z - to.z) > EPS
                    || from.y < to.y || from.y - to.y > 1 + EPS || !physical.clearSegment(from, to, width, height)) return false;
            AABB swept = body(from, width, height).minmax(body(to, width, height));
            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(swept.minX - 1, swept.minY - 1, swept.minZ - 1),
                    BlockPos.containing(swept.maxX + 1, swept.maxY + 1, swept.maxZ + 1))) {
                BlockState state = view.getBlockState(pos);
                if (forbidden.contains(pos.asLong()) && swept.intersects(new AABB(pos))
                        || TransportLanding.unsafe(view, pos, state) && swept.inflate(EPS * 2).intersects(new AABB(pos))) return false;
                for (AABB box : state.getCollisionShape(view, pos).toAabbs()) if (swept.intersects(box.move(pos))) return false;
            }
            return ground().clear(to, to);
        }
        List<Vec3> exit(Vec3 origin, Set<BlockPos> scaffolds) {
            // 沿用 BuildSupplyExit 的地面标准：五乘五内至少九个可达落脚点，并有真实二乘二地面，不能只剩孤台或一格桥。
            BlockPos base = BlockPos.containing(origin);
            if (scaffolds.contains(base.below()) || !ground().clear(origin, origin)) return List.of();
            var walking = new BuildSupportWalking(view, view.loaded, width, height, forbidden, physical);
            var reached = new LinkedHashMap<BlockPos, Node>(); var queue = new ArrayDeque<Node>();
            var start = new Node(origin, null); reached.put(base, start); queue.add(start);
            while (!queue.isEmpty() && reached.size() < 25) {
                Node node = queue.removeFirst(); BlockPos at = BlockPos.containing(node.feet);
                for (Direction side : SIDES) for (int dy : new int[]{0, 1, -1}) {
                    BlockPos next = at.relative(side).offset(0, dy, 0);
                    if (Math.abs(next.getX() - base.getX()) > 2 || Math.abs(next.getZ() - base.getZ()) > 2
                            || Math.abs(next.getY() - base.getY()) > 2 || reached.containsKey(next) || scaffolds.contains(next.below())) continue;
                    Vec3 feet = walking.stance(next);
                    if (feet == null || !walking.edge(node.feet, feet)) continue;
                    BlockPos actual = BlockPos.containing(feet);
                    if (scaffolds.contains(actual.below()) || reached.containsKey(actual) || reached.size() >= 25) continue;
                    var found = new Node(feet, node); reached.put(actual, found); queue.add(found);
                }
            }
            if (reached.size() < 9 || reached.keySet().stream().noneMatch(pos -> reached.containsKey(pos.east())
                    && reached.containsKey(pos.south()) && reached.containsKey(pos.east().south()))) return List.of();
            Node end = reached.values().stream().filter(node -> node.feet.distanceToSqr(origin) >= 1)
                    .min(Comparator.comparingDouble(node -> node.feet.distanceToSqr(origin))).orElse(null);
            if (end == null) return List.of();
            var route = new ArrayList<Vec3>(); for (Node at = end; at != null; at = at.previous) route.add(at.feet);
            Collections.reverse(route); return List.copyOf(route);
        }
        private record Node(Vec3 feet, Node previous) {}
    }

    // 所有步骤共享有限的原始方块观察；确认过的前缀可变为空气，其余已观察方块变动必须使旧计划失效。
    static final class View implements BlockGetter {
        final BlockGetter live;
        final Predicate<BlockPos> loaded;
        final Map<BlockPos, BlockState> observed;
        final Set<BlockPos> removed;
        View(BlockGetter live, Predicate<BlockPos> loaded) { this(live, loaded, new LinkedHashMap<>(), Set.of()); }
        private View(BlockGetter live, Predicate<BlockPos> loaded, Map<BlockPos, BlockState> observed, Set<BlockPos> removed) {
            this.live = live; this.loaded = loaded; this.observed = observed; this.removed = Set.copyOf(removed);
        }
        View without(Set<BlockPos> cells) { return new View(live, loaded, observed, cells); }
        boolean current(Set<BlockPos> gone) {
            for (var entry : observed.entrySet()) {
                if (!loaded.test(entry.getKey())) return false;
                var actual = live.getBlockState(entry.getKey());
                if (gone.contains(entry.getKey()) ? !actual.isAir() : !actual.equals(entry.getValue())) return false;
            }
            return true;
        }
        public BlockState getBlockState(BlockPos pos) {
            if (!loaded.test(pos)) throw new IllegalStateException("descent_unloaded");
            BlockState actual = observed.get(pos);
            if (actual == null) {
                if (observed.size() >= 8192) throw new IllegalStateException("descent_observation_budget");
                actual = live.getBlockState(pos); observed.put(pos.immutable(), actual);
            }
            return removed.contains(pos) ? Blocks.AIR.defaultBlockState() : actual;
        }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos pos) {
            if (!loaded.test(pos)) throw new IllegalStateException("descent_unloaded");
            return removed.contains(pos) ? null : live.getBlockEntity(pos);
        }
        public int getMinBuildHeight() { return live.getMinBuildHeight(); }
        public int getHeight() { return live.getHeight(); }
    }
    static AABB body(Vec3 feet, double width, double height) {
        return new AABB(feet.x - width / 2 + EPS, feet.y + EPS, feet.z - width / 2 + EPS,
                feet.x + width / 2 - EPS, feet.y + height - EPS, feet.z + width / 2 - EPS);
    }
    static boolean finite(Vec3 value) { return value != null && Double.isFinite(value.lengthSqr()); }
}
