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
        default Map<String, Object> obstruction(Vec3 from, Vec3 to) {
            return Map.of("from", from.toString(), "to", to.toString(), "reason", "swept body corridor obstructed");
        }
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
        // Manhattan distance is exact in open six-axis space: thousands of cells can share f.
        // Break that plateau toward the goal instead of flood-filling the whole intervening volume.
        private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score)
                .thenComparing(Comparator.comparingDouble(Node::cost).reversed()));
        private final Map<BlockPos, Double> costs = new HashMap<>();
        private final Map<BlockPos, BlockPos> previous = new HashMap<>();
        private final Map<BlockPos, Double> clearanceCosts = new HashMap<>();
        private BlockPos origin, goal;
        private Vec3 landing;
        private List<Vec3> points;
        private final List<Vec3> exits = new ArrayList<>();
        private double ticks = 80;
        private final int nodeLimit;
        private final boolean landingGoal;
        private int expanded, validatedPoints, initialization, templatePoint = 1;
        private List<Vec3> template;
        private boolean done;
        private Plan result;
        private String failureReason;
        public Search(Vec3 start, Vec3 target, JetpackNativeAdapter.Snapshot power) {
            this(start, target, power, 6000);
        }
        public Search(Vec3 start, Vec3 target, JetpackNativeAdapter.Snapshot power, int nodeLimit) {
            this(start,target,power,nodeLimit,true);
        }
        public Search(Vec3 start, Vec3 target, JetpackNativeAdapter.Snapshot power, int nodeLimit, boolean landingGoal) {
            if (nodeLimit < 0) throw new IllegalArgumentException("negative search node limit");
            this.start = start; this.target = target; this.power = power;
            this.nodeLimit = nodeLimit;
            this.landingGoal = landingGoal;
        }
        public boolean done() { return done; }
        public Plan result() { return result; }
        /** Null while running or successful; budget exhaustion does not prove a corridor absent. */
        public String failureReason() { return failureReason; }
        /** Valid nodes whose neighbors were expanded; stale queue entries and templates cost no nodes. */
        public int expanded() { return expanded; }
        public void advance(Space space, int nodeBudget, long timeBudgetNanos) {
            if (done) return;
            long began = System.nanoTime();
            int operations = 0;
            while (operations++ < nodeBudget && System.nanoTime() - began < timeBudgetNanos) {
                // Initial body sweeps and the optional template also yield between operations.
                if (initialization < 4) {
                    initialize(space);
                    if (done) return;
                    continue;
                }
                if (points != null) {
                    int i = validatedPoints;
                    if (i == points.size()) {
                        result = new Plan(points, exits, (int) Math.ceil(ticks + 60));
                        done = true; return;
                    }
                    Vec3 point = points.get(i), exit = space.landingBelow(point.add(0, 0.1, 0));
                    if (i > 0 && !(landingGoal && i == points.size() - 1 ? space.clear(points.get(i - 1), point)
                            : flightClear(space, points.get(i - 1), point, power))) { fail("corridor_changed"); return; }
                    if (exit != null) exits.add(exit);
                    validatedPoints++;
                    if (i > 0) ticks += edgeTicks(points.get(i - 1), point, power);
                    continue;
                }
                if (open.isEmpty()) { fail("no_corridor"); return; }
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
                if (expanded >= nodeLimit) { fail("budget_exhausted"); return; }
                expanded++;
                for (int[] direction : DIRECTIONS) {
                    BlockPos next = node.pos().offset(direction[0], direction[1], direction[2]);
                    if (Math.abs(next.getX() - origin.getX()) > 64 || Math.abs(next.getZ() - origin.getZ()) > 64
                            || next.getY() < Math.min(origin.getY(), goal.getY()) - 4
                            || next.getY() > Math.max(origin.getY(), goal.getY()) + 8) continue;
                    double cost = node.cost() + 1;
                    if (cost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)
                            || !flightClear(space, center(node.pos()), center(next), power)) continue;
                    cost += clearanceCosts.computeIfAbsent(next,
                            p -> JetpackClearancePolicy.clearancePenalty(space, center(p), power));
                    if (cost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                    costs.put(next, cost); previous.put(next, node.pos());
                    open.add(new Node(next, cost, cost + distance(next, goal)));
                }
            }
        }
        private void fail(String reason) { failureReason = reason; done = true; }
        private void initialize(Space space) {
            if (initialization == 0) {
                if (!power.controllable()) { fail("uncontrollable"); return; }
                if (start.distanceTo(target) > 64) { fail("out_of_range"); return; }
                if (!landingGoal) {
                    if (!flightClear(space,start,target,power)) { fail("corridor_changed"); return; }
                    points=new ArrayList<>(List.of(start,target)); initialization=4; return;
                }
                landing = space.landingBelow(target.add(0, 0.1, 0));
                if (landing == null || Math.abs(landing.y - target.y) > 1.01) { fail("no_landing"); return; }
                origin = BlockPos.containing(start.x, Math.ceil(start.y) + 1, start.z);
                initialization++;
            } else if (initialization == 1) {
                goal = approachCell(space, landing, power);
                if (goal == null) { fail("no_approach"); return; }
                initialization++;
            } else if (initialization == 2) {
                if (!flightClear(space, start, center(origin), power)) { fail("departure_blocked"); return; }
                Vec3 lift = new Vec3(start.x, Math.max(origin.getY(), goal.getY()), start.z);
                template = List.of(start, lift, new Vec3(landing.x, lift.y, landing.z), landing);
                open.add(new Node(origin, 0, distance(origin, goal))); costs.put(origin, 0D);
                initialization++;
            } else {
                // Prefer climb/cruise/descent only when its optional cruise margin is also clear.
                Vec3 from = template.get(templatePoint - 1), to = template.get(templatePoint);
                boolean clear = templatePoint == 3 ? space.clear(from, to) : flightClear(space, from, to, power);
                if (clear && templatePoint == 2)
                    clear = JetpackClearancePolicy.edgePenalty(space, from, to, power) == 0;
                if (!clear || ++templatePoint == template.size()) {
                    if (clear) points = new ArrayList<>(template);
                    template = null;
                    initialization++;
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
    static BlockPos approachCell(Space space, Vec3 landing, JetpackNativeAdapter.Snapshot power) {
        // Prefer a two-block reserve above the platform; a low ceiling may only admit one.
        // The whole final descent column must be observed before choosing that staging height.
        for (int clearance = 2; clearance >= 1; clearance--) {
            BlockPos candidate = BlockPos.containing(landing.x, Math.ceil(landing.y) + clearance, landing.z);
            if (space.clear(center(candidate), landing) && flightClear(space, center(candidate), center(candidate), power)) return candidate;
        }
        return null;
    }

    static boolean flightClear(Space space, Vec3 from, Vec3 to, JetpackNativeAdapter.Snapshot power) {
        double hoverVelocity = JetpackDynamics.rawAfterStep(power.hoverDescent(), power);
        double reserve = JetpackDynamics.riseEnvelope(hoverVelocity, true, power);
        return space.clear(from, to) && space.clear(from.add(0, reserve, 0), to.add(0, reserve, 0));
    }

    static boolean supportsLanding(Space space, Vec3 landing) {
        Vec3 observed = space.landingBelow(landing.add(0, 0.1, 0));
        return observed != null && observed.distanceToSqr(landing) < 0.01;
    }

    /** Look ahead only inside the current clear height band or ascent column; never skip into touchdown. */
    static int nextWaypoint(Space space, Plan route, Vec3 position, int current, JetpackNativeAdapter.Snapshot power) {
        int last = route.points().size() - 2;
        current = Math.min(current, last);
        Vec3 point = route.points().get(current);
        if (current < last && Math.hypot(position.x - point.x, position.z - point.z) < 0.45
                && atWaypointHeight(route, current, position.y)
                && flightClear(space, position, route.points().get(current + 1), power)) current++;
        point = route.points().get(current);
        boolean atHeight = atWaypointHeight(route, current, position.y);
        for (int i = current + 1; i <= last; i++) {
            Vec3 candidate = route.points().get(i);
            boolean level = Math.abs(candidate.y - point.y) < 0.01 && atHeight;
            boolean vertical = Math.hypot(candidate.x - point.x, candidate.z - point.z) < 0.01;
            if ((!level && !vertical) || position.distanceTo(candidate) > 6 || !flightClear(space, position, candidate, power)) break;
            // Near an opening, keep its interior waypoint instead of shaving off a clear but tight corner.
            // Normal arrival advancement above still admits narrow passages.
            if (level && JetpackClearancePolicy.edgePenalty(space, position, candidate, power) > 0) break;
            current = i;
        }
        return current;
    }
    static boolean atWaypointHeight(Plan route, int index, double height) {
        double target = route.points().get(index).y;
        boolean descending = index > 0 && target < route.points().get(index - 1).y - 0.01;
        return height >= target - 0.1 && (!descending || height <= target + 0.1);
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
                AABB origins = box.inflate(1.0000001);
                if (!ctx.level().hasChunksAt(BlockPos.containing(origins.minX,origins.minY,origins.minZ),
                        BlockPos.containing(origins.maxX,origins.maxY,origins.maxZ))) return false;
                for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                        BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
                    if (!ctx.level().hasChunkAt(p)) return false;
                    var state = ctx.level().getBlockState(p);
                    if (!state.getFluidState().isEmpty() || hazard(state)
                            || forbidden.contains(p.asLong())) return false;
                }
                return ctx.level().noCollision(ctx.player(), box)
                        && org.maiwithu.maicraft.core.integration.physics.SableStructureBridge.clearBody(ctx.level(), box);
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
            public Map<String, Object> obstruction(Vec3 from, Vec3 to) {
                return JetpackObstruction.inspect(ctx, forbidden, from, to);
            }
        };
    }
    private static boolean hazard(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.POINTED_DRIPSTONE) || state.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.CampfireBlock;
    }
}
