// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import baritone.pathing.movement.CollisionGeometry;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;

/** Bounded six-direction flight search through loaded, dry, collision-free body corridors. */
public final class JetpackRoute {
    public interface Space {
        boolean clear(Vec3 from, Vec3 to);
        Vec3 landingBelow(Vec3 point);
    }
    public record Plan(List<Vec3> points, List<Vec3> emergencyLandings, int requiredTicks) {
        public Plan { points = List.copyOf(points); emergencyLandings = List.copyOf(emergencyLandings); }
    }
    private record Node(BlockPos pos, double cost, double score) {}
    private static final int[][] DIRECTIONS = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

    /** Only coordinates and immutable numbers survive a tick; every world query uses the fresh Space. */
    public static final class Search {
        private final Vec3 start, target;
        private final JetpackNativeAdapter.Snapshot power;
        private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
        private final Map<BlockPos, Double> costs = new HashMap<>();
        private final Map<BlockPos, BlockPos> previous = new HashMap<>();
        private BlockPos origin, goal;
        private Vec3 landing;
        private List<Vec3> points;
        private final List<Vec3> exits = new ArrayList<>();
        private double ticks = 80;
        private int expanded, validatedPoints;
        private boolean done;
        private Plan result;
        public Search(Vec3 start, Vec3 target, JetpackNativeAdapter.Snapshot power) {
            this.start = start; this.target = target; this.power = power;
        }
        public boolean done() { return done; }
        public Plan result() { return result; }
        public int expanded() { return expanded; }
        public void advance(Space space, int nodeBudget, long timeBudgetNanos) {
            if (done) return;
            long began = System.nanoTime();
            if (origin == null) {
                if (!power.controllable() || start.distanceTo(target) > 64) { done = true; return; }
                landing = space.landingBelow(target.add(0, 0.1, 0));
                if (landing == null || Math.abs(landing.y - target.y) > 1.01) { done = true; return; }
                origin = BlockPos.containing(start.x, Math.ceil(start.y) + 1, start.z);
                goal = approachCell(space, landing);
                if (goal == null) { done = true; return; }
                if (!space.clear(start, center(origin))) { done = true; return; }
                open.add(new Node(origin, 0, distance(origin, goal))); costs.put(origin, 0D);
            }
            int operations = 0;
            while (operations++ < nodeBudget && System.nanoTime() - began < timeBudgetNanos) {
                if (points != null) {
                    int i = validatedPoints;
                    if (i == points.size()) {
                        result = new Plan(points, exits, (int) Math.ceil(ticks + 60));
                        done = true; return;
                    }
                    Vec3 point = points.get(i), exit = space.landingBelow(point.add(0, 0.1, 0));
                    if (i > 0 && !space.clear(points.get(i - 1), point)) { done = true; return; }
                    if (exit != null) exits.add(exit);
                    validatedPoints++;
                    if (i > 0) ticks += edgeTicks(points.get(i - 1), point, power);
                    continue;
                }
                if (open.isEmpty() || expanded++ >= 6000) { done = true; return; }
                Node node = open.poll();
                if (node.cost() != costs.getOrDefault(node.pos(), Double.POSITIVE_INFINITY)) continue;
                if (node.pos().equals(goal)) {
                    var cells = new ArrayList<BlockPos>();
                    for (BlockPos p = goal; p != null; p = previous.get(p)) cells.add(p);
                    Collections.reverse(cells);
                    points = new ArrayList<>(); points.add(start); points.add(center(origin));
                    for (int i = 1; i < cells.size(); i++) points.add(center(cells.get(i)));
                    points.add(landing); continue;
                }
                for (int[] direction : DIRECTIONS) {
                    BlockPos next = node.pos().offset(direction[0], direction[1], direction[2]);
                    if (Math.abs(next.getX() - origin.getX()) > 64 || Math.abs(next.getZ() - origin.getZ()) > 64
                            || next.getY() < Math.min(origin.getY(), goal.getY()) - 4
                            || next.getY() > Math.max(origin.getY(), goal.getY()) + 8) continue;
                    double cost = node.cost() + 1;
                    if (cost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)
                            || !space.clear(center(node.pos()), center(next))) continue;
                    costs.put(next, cost); previous.put(next, node.pos());
                    open.add(new Node(next, cost, cost + distance(next, goal)));
                }
            }
        }
    }

    public static double edgeTicks(Vec3 from, Vec3 to, JetpackNativeAdapter.Snapshot power) {
        double horizontal = Math.hypot(to.x - from.x, to.z - from.z);
        double vertical = Math.abs(to.y - from.y);
        // Slow approach, braking and mode/landing reserves are included; no maximum-speed promise.
        return 12 + horizontal / Math.min(0.08, power.horizontal() * 3)
                + vertical / (to.y > from.y ? Math.min(0.12, (power.vertical() - power.gravity()) * 0.5) : descentSpeed(power));
    }
    public static double descentSpeed(JetpackNativeAdapter.Snapshot power) {
        return -power.hoverDescent();
    }
    static BlockPos approachCell(Space space, Vec3 landing) {
        // Prefer a two-block reserve above the platform; a low ceiling may only admit one.
        // The whole final descent column must be observed before choosing that staging height.
        for (int clearance = 2; clearance >= 1; clearance--) {
            BlockPos candidate = BlockPos.containing(landing.x, Math.ceil(landing.y) + clearance, landing.z);
            if (space.clear(center(candidate), landing)) return candidate;
        }
        return null;
    }
    static Vec3 projectedPosition(Vec3 position, Vec3 velocity, boolean onGround) {
        // Gravity can leave downward delta movement after the floor has resolved contact.
        // Predict the supported body's motion, not a fictitious sweep through that floor.
        double vertical = onGround ? Math.max(0, velocity.y) : velocity.y;
        return position.add(velocity.x * 3, vertical * 3, velocity.z * 3);
    }
    private static Vec3 center(BlockPos p) { return new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5); }
    private static double distance(BlockPos a, BlockPos b) {
        return Math.abs(a.getX()-b.getX()) + Math.abs(a.getY()-b.getY()) + Math.abs(a.getZ()-b.getZ());
    }

    /** This view is used only within the current client tick; a session never retains it. */
    public static Space observed(LocalPlayerContext ctx) {
        return observed(ctx, org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext.forbiddenBodyCells());
    }
    public static Space observed(LocalPlayerContext ctx, it.unimi.dsi.fastutil.longs.LongSet forbidden) {
        return new Space() {
            private boolean body(Vec3 feet) {
                double half = ctx.player().getBbWidth() * 0.5 + 0.08;
                AABB box = new AABB(feet.x-half, feet.y+0.001, feet.z-half,
                        feet.x+half, feet.y+ctx.player().getBbHeight()+0.08, feet.z+half);
                if (box.minY < ctx.level().getMinBuildHeight() || box.maxY >= ctx.level().getMaxBuildHeight()
                        || !ctx.level().getWorldBorder().isWithinBounds(box)) return false;
                for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                        BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
                    if (!ctx.level().hasChunkAt(p)) return false;
                    var state = ctx.level().getBlockState(p);
                    if (!state.getFluidState().isEmpty() || hazard(state)
                            || forbidden.contains(p.asLong())) return false;
                }
                return ctx.level().noCollision(ctx.player(), box);
            }
            public boolean clear(Vec3 from, Vec3 to) {
                int samples = Math.max(1, (int) Math.ceil(from.distanceTo(to) / 0.2));
                if (samples > 400) return false;
                for (int i = 0; i <= samples; i++) if (!body(from.lerp(to, (double) i / samples))) return false;
                return true;
            }
            public Vec3 landingBelow(Vec3 point) {
                BlockPos column = BlockPos.containing(point);
                for (int y = column.getY(); y >= Math.max(ctx.level().getMinBuildHeight(), column.getY()-64); y--) {
                    BlockPos support = new BlockPos(column.getX(), y, column.getZ());
                    if (!ctx.level().hasChunkAt(support)) return null;
                    var state = ctx.level().getBlockState(support);
                    if (!state.getFluidState().isEmpty() || hazard(state)) return null;
                    double height = CollisionGeometry.supportHeight(ctx.level(), support);
                    if (Double.isNaN(height)) continue;
                    Vec3 feet = new Vec3(point.x, y + height, point.z);
                    if (feet.y > point.y + 0.02) continue;
                    return clear(point, feet) ? feet : null;
                }
                return null;
            }
        };
    }
    private static boolean hazard(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.POINTED_DRIPSTONE) || state.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.CampfireBlock;
    }
}
