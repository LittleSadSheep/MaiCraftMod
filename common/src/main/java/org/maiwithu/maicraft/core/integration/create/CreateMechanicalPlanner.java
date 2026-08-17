// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/** Tick-sliced, preserve-existing mechanical route planning. */
final class CreateMechanicalPlanner {
    private static final Direction[] VERTICAL = {Direction.UP, Direction.DOWN};

    private CreateMechanicalPlanner() {}

    static CreateMechanicalPlan.Result plan(
            LocalPlayer player, CreateMechanicalPower.Request request) {
        Map<String, Object> facts = CreateMechanicalPlan.facts();
        CreateMechanicalPower.Availability availability = CreateKineticsBridge.availability();
        if (!availability.available()) {
            facts.put("failure_code", "optional_dependency_unavailable");
            facts.put("detail", availability.detail());
            facts.put("recovery_options", List.of("install_or_enable_create", "cancel"));
            return CreateMechanicalPlan.Result.fail("optional_dependency_unavailable", facts);
        }
        if (!request.preserveExisting()) {
            facts.put("failure_code", "preserve_existing_required");
            facts.put("detail", "mechanical routing will not clear or replace existing blocks");
            facts.put("recovery_options", List.of("retry_with_preserve_existing", "cancel"));
            return CreateMechanicalPlan.Result.fail("preserve_existing_required", facts);
        }
        // Endpoint discovery and route planning are intentionally delegated to progressive,
        // tick-sliced searches. A synchronous expanding scan would freeze the client precisely on
        // the large but legitimate jobs this integration is meant to support, while any finite
        // synchronous radius would turn absence inside an arbitrary circle into false evidence.
        facts.put("failure_code", "route_needs_exploration");
        facts.put("detail", "semantic endpoint anchors require progressive live-world evidence before preserving route construction");
        facts.put("endpoint_search", "progressive_nearest_evidence");
        facts.put("route_search", "world_bounded_3d_chain_drive_state_a_star");
        facts.put("recovery_options", List.of(
                "continue_progressive_endpoint_and_route_search", "choose_other_endpoint", "cancel"));
        return CreateMechanicalPlan.Result.fail("route_needs_exploration", facts);
    }

    private static BlockPos findStand(ClientLevel level, BlockPos target, Set<BlockPos> route) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos feet = target.offset(dx, dy, dz);
                    if (route.contains(feet) || route.contains(feet.above())) continue;
                    candidates.add(feet);
                }
            }
        }
        candidates.sort(Comparator.comparingDouble((BlockPos position) -> position.distSqr(target))
                .thenComparingLong(BlockPos::asLong));
        for (BlockPos feet : candidates) {
            if (standable(level, feet) && withinPlacementReach(feet, target)
                    && verticalAxisPlacementAngle(feet, target)) return feet.immutable();
        }
        return null;
    }

    private static boolean standable(ClientLevel level, BlockPos feet) {
        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        if (!level.isLoaded(feet) || !level.isLoaded(head) || !level.isLoaded(floor)) return false;
        if (NavigationSafetyContext.forbidsBody(feet)
                || NavigationSafetyContext.protectsMutation(feet)
                || NavigationSafetyContext.protectsMutation(head)) return false;
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(head);
        if (!feetState.getFluidState().isEmpty() || !headState.getFluidState().isEmpty()) return false;
        // Use the exact same passability contract as PlayerNav: carpets, hand-openable doors,
        // paths, stairs and slabs are real existing corridors, not "occupied" failures. Create
        // remains stricter about fluids, crops/farmland and entities because this is a stationary
        // precision-placement stance rather than an ordinary transit node.
        if (!BlockHelper.isStandable(level, feet)
                || BlockHelper.isHazard(level, feet)
                || BlockHelper.isHazard(level, head)
                || BlockHelper.isHazard(level, floor)
                || protectedTerrain(level, feet)
                || protectedTerrain(level, floor)) return false;
        AABB body = new AABB(feet.getX() + 0.2, feet.getY(), feet.getZ() + 0.2,
                feet.getX() + 0.8, feet.getY() + 1.8, feet.getZ() + 0.8);
        return level.getEntities(null, body).isEmpty();
    }

    private static boolean withinPlacementReach(BlockPos feet, BlockPos target) {
        double eyeX = feet.getX() + 0.5;
        double eyeY = feet.getY() + 1.62;
        double eyeZ = feet.getZ() + 0.5;
        double dx = target.getX() + 0.5 - eyeX;
        double dy = target.getY() + 0.5 - eyeY;
        double dz = target.getZ() + 0.5 - eyeZ;
        return dx * dx + dy * dy + dz * dz <= 4.35 * 4.35;
    }

    private static boolean verticalAxisPlacementAngle(BlockPos feet, BlockPos target) {
        double dx = target.getX() - feet.getX();
        double dz = target.getZ() - feet.getZ();
        double dy = target.getY() + 0.5 - (feet.getY() + 1.62);
        return Math.toDegrees(Math.atan2(Math.abs(dy), Math.sqrt(dx * dx + dz * dz))) >= 48.0;
    }

    static boolean isEmptyRouteCell(ClientLevel level, BlockPos position) {
        if (!level.isLoaded(position) || level.isOutsideBuildHeight(position)
                || !level.getWorldBorder().isWithinBounds(position)) return false;
        if (NavigationSafetyContext.protectsMutation(position)
                || NavigationSafetyContext.forbidsBody(position)) return false;
        BlockState state = level.getBlockState(position);
        if (!state.isAir() || !state.getFluidState().isEmpty()
                || level.getBlockEntity(position) != null) return false;
        return !protectedTerrain(level, position.below());
    }

    static boolean isFreeReceiverCell(ClientLevel level, BlockPos position) {
        return isEmptyRouteCell(level, position)
                && level.getBlockEntity(position.below()) == null
                && !protectedTerrain(level, position.below());
    }

    private static boolean protectedTerrain(ClientLevel level, BlockPos position) {
        if (!level.isLoaded(position)) return true;
        BlockState state = level.getBlockState(position);
        Block block = state.getBlock();
        return state.is(Blocks.FARMLAND) || block instanceof CropBlock || block instanceof StemBlock
                || block instanceof AttachedStemBlock || block instanceof CocoaBlock
                || state.is(Blocks.NETHER_WART) || state.is(Blocks.SWEET_BERRY_BUSH);
    }

    private static boolean hasDuplicate(List<BlockPos> route) {
        return new HashSet<>(route).size() != route.size();
    }

    enum ObstacleRouteStatus { RUNNING, FOUND, EXHAUSTED }

    /**
     * Incremental 3D route search used by the progressive survey. A state is a candidate chain
     * drive cell plus its horizontal chain-connection axis. Create's own propagation contract is
     * mirrored here: horizontal neighbours connect only when both drives select the movement axis,
     * while vertical neighbours connect through their shared Y shaft. A turn therefore happens by
     * stepping vertically and selecting the other horizontal connection axis, not by pretending a
     * single drive can bend an X chain into Z.
     *
     * <p>Each graph instance is one finite physical envelope around the two endpoints. Its owner
     * discards an exhausted instance and expands the envelope, without a total-distance cutoff,
     * until Minecraft's build height and world border are covered. Loaded transitions verify
     * target emptiness, support continuity, and at least one first-person placement stance;
     * unloaded transitions remain provisional until progressive physical survey.</p>
     */
    static final class ObstacleAwareRouteSearch {
        private enum SearchStep { RUNNING, FOUND, EXHAUSTED }

        private record Bounds(
                int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                boolean worldExhausted) {
            boolean contains(BlockPos position) {
                return position.getX() >= minX && position.getX() <= maxX
                        && position.getY() >= minY && position.getY() <= maxY
                        && position.getZ() >= minZ && position.getZ() <= maxZ;
            }
        }

        private record RouteState(
                BlockPos position, Direction.Axis connectionAxis, Direction lastMove) {}
        private record PlacementEdge(BlockPos support, BlockPos target) {}
        private record OpenNode(RouteState state, long cost, long estimate) {}

        private final ClientLevel level;
        private final CreateMechanicalPlan.KineticEndpoint source;
        private final CreateMechanicalPlan.KineticEndpoint destination;
        private final BlockPos receiver;
        private final BlockPos start;
        private final Set<Long> rejectedCells;
        private final Bounds bounds;
        private final boolean startAllowed;
        private final PriorityQueue<OpenNode> open = new PriorityQueue<>(
                Comparator.comparingLong(OpenNode::estimate)
                        .thenComparingLong(OpenNode::cost)
                        .thenComparingLong(node -> node.state().position().asLong())
                        .thenComparingInt(node -> node.state().connectionAxis().ordinal())
                        .thenComparingInt(node -> node.state().lastMove() == null
                                ? -1 : node.state().lastMove().ordinal()));
        private final Map<RouteState, Long> bestCost = new HashMap<>();
        private final Map<RouteState, RouteState> cameFrom = new HashMap<>();
        private final Set<RouteState> closed = new HashSet<>();
        private final Map<PlacementEdge, Boolean> loadedPlacementEdges = new HashMap<>();
        private TentativeRoute route;
        private long progressRevision;

        private ObstacleAwareRouteSearch(
                ClientLevel level,
                CreateMechanicalPlan.KineticEndpoint source,
                CreateMechanicalPlan.KineticEndpoint destination,
                BlockPos receiver,
                Set<Long> rejectedCells,
                int envelopePadding) {
            this.level = level;
            this.source = source;
            this.destination = destination;
            this.receiver = receiver.immutable();
            this.start = source.position().relative(source.shaftFace()).immutable();
            this.rejectedCells = Set.copyOf(rejectedCells);
            this.bounds = bounds(level, start, receiver, envelopePadding);
            this.startAllowed = startPlacementAllowed();
            if (startAllowed) {
                addRoot(Direction.Axis.X);
                addRoot(Direction.Axis.Z);
            }
        }

        ObstacleRouteStatus tick(int expansionBudget) {
            int remaining = Math.max(1, expansionBudget);
            while (remaining-- > 0) {
                SearchStep step = step();
                progressRevision++;
                if (step == SearchStep.RUNNING) continue;
                return step == SearchStep.FOUND
                        ? ObstacleRouteStatus.FOUND : ObstacleRouteStatus.EXHAUSTED;
            }
            return ObstacleRouteStatus.RUNNING;
        }

        TentativeRoute route() { return route; }
        long progressRevision() { return progressRevision; }
        boolean worldEnvelopeExhausted() { return bounds.worldExhausted(); }
        boolean envelopeExpansionMayHelp() { return startAllowed && !bounds.worldExhausted(); }

        private void addRoot(Direction.Axis axis) {
            RouteState root = new RouteState(start, axis, null);
            bestCost.put(root, 0L);
            open.add(new OpenNode(root, 0L, heuristic(start)));
        }

        private SearchStep step() {
            OpenNode node = open.poll();
            if (node == null) return SearchStep.EXHAUSTED;
            RouteState state = node.state();
            Long known = bestCost.get(state);
            if (known == null || known.longValue() != node.cost()
                    || !closed.add(state)) return SearchStep.RUNNING;
            if (!cellAllowed(state.position())) return SearchStep.RUNNING;
            if (state.position().equals(receiver)) {
                List<BlockPos> positions = reconstruct(state);
                if (!positions.isEmpty() && !hasDuplicate(positions)) {
                    String geometry = CreateMechanicalPlan.geometry(positions);
                    String routeHash = CreateMechanicalPlan.hashRoute(positions, geometry);
                    route = new TentativeRoute(source, destination, receiver,
                            positions, geometry, routeHash);
                    return SearchStep.FOUND;
                }
                return SearchStep.RUNNING;
            }
            for (RouteState next : neighbours(state)) {
                if (closed.contains(next) || !transitionAllowed(state, next)) continue;
                long candidate = node.cost() + transitionCost(state, next);
                Long prior = bestCost.get(next);
                if (prior != null && prior <= candidate) continue;
                bestCost.put(next, candidate);
                cameFrom.put(next, state);
                open.add(new OpenNode(next, candidate,
                        candidate + heuristic(next.position())));
            }
            return SearchStep.RUNNING;
        }

        private List<RouteState> neighbours(RouteState state) {
            List<RouteState> result = new ArrayList<>(6);
            Direction[] along = state.connectionAxis() == Direction.Axis.X
                    ? new Direction[]{Direction.WEST, Direction.EAST}
                    : new Direction[]{Direction.NORTH, Direction.SOUTH};
            for (Direction direction : along) addNeighbour(result, state, direction,
                    state.connectionAxis());
            for (Direction direction : VERTICAL) {
                addNeighbour(result, state, direction, Direction.Axis.X);
                addNeighbour(result, state, direction, Direction.Axis.Z);
            }
            return result;
        }

        private void addNeighbour(
                List<RouteState> result,
                RouteState state,
                Direction direction,
                Direction.Axis nextConnectionAxis) {
            if (state.lastMove() != null
                    && direction == state.lastMove().getOpposite()) return;
            result.add(new RouteState(state.position().relative(direction).immutable(),
                    nextConnectionAxis, direction));
        }

        private boolean transitionAllowed(RouteState from, RouteState to) {
            Direction direction = CreateMechanicalPlan.between(
                    from.position(), to.position());
            if (direction == null || !mechanicallyContinuous(from, to, direction)
                    || !cellAllowed(to.position())) return false;
            if (!level.isLoaded(from.position()) || !level.isLoaded(to.position())) return true;
            PlacementEdge edge = new PlacementEdge(from.position(), to.position());
            return loadedPlacementEdges.computeIfAbsent(edge, ignored ->
                    findStand(level, to.position(), Set.of(
                            from.position(), to.position())) != null);
        }

        private static boolean mechanicallyContinuous(
                RouteState from, RouteState to, Direction direction) {
            if (direction.getAxis() == Direction.Axis.Y) return true;
            return from.connectionAxis() == direction.getAxis()
                    && to.connectionAxis() == direction.getAxis();
        }

        private long transitionCost(RouteState from, RouteState to) {
            Direction direction = CreateMechanicalPlan.between(
                    from.position(), to.position());
            long cost = direction != null && direction.getAxis() == Direction.Axis.Y ? 2L : 1L;
            if (!level.isLoaded(to.position())) cost++;
            return cost;
        }

        private boolean startPlacementAllowed() {
            if (!cellAllowed(start)) return false;
            BlockPos support = source.position();
            if (!level.isLoaded(start) || !level.isLoaded(support)) return true;
            return findStand(level, start, Set.of(support, start)) != null;
        }

        private boolean cellAllowed(BlockPos position) {
            if (!bounds.contains(position)
                    || rejectedCells.contains(position.asLong())
                    || level.isOutsideBuildHeight(position)
                    || !level.getWorldBorder().isWithinBounds(position)) return false;
            return !level.isLoaded(position) || isEmptyRouteCell(level, position);
        }

        private long heuristic(BlockPos position) {
            return Math.abs((long) position.getX() - receiver.getX())
                    + Math.abs((long) position.getY() - receiver.getY())
                    + Math.abs((long) position.getZ() - receiver.getZ());
        }

        private List<BlockPos> reconstruct(RouteState goal) {
            List<BlockPos> reverse = new ArrayList<>();
            RouteState cursor = goal;
            while (cursor != null) {
                reverse.add(cursor.position());
                cursor = cameFrom.get(cursor);
            }
            List<BlockPos> forward = new ArrayList<>(reverse.size());
            for (int i = reverse.size() - 1; i >= 0; i--) {
                forward.add(reverse.get(i).immutable());
            }
            return List.copyOf(forward);
        }

        private static Bounds bounds(
                ClientLevel level, BlockPos start, BlockPos receiver, int padding) {
            int safePadding = Math.max(1, padding);
            var border = level.getWorldBorder();
            int worldMinX = (int) Math.ceil(border.getMinX());
            int worldMaxX = (int) Math.floor(Math.nextDown(border.getMaxX()));
            int worldMinZ = (int) Math.ceil(border.getMinZ());
            int worldMaxZ = (int) Math.floor(Math.nextDown(border.getMaxZ()));
            int worldMinY = level.getMinBuildHeight();
            int worldMaxY = level.getMaxBuildHeight() - 1;
            int minX = clampToWorld((long) Math.min(start.getX(), receiver.getX()) - safePadding,
                    worldMinX, worldMaxX);
            int maxX = clampToWorld((long) Math.max(start.getX(), receiver.getX()) + safePadding,
                    worldMinX, worldMaxX);
            int minY = clampToWorld((long) Math.min(start.getY(), receiver.getY()) - safePadding,
                    worldMinY, worldMaxY);
            int maxY = clampToWorld((long) Math.max(start.getY(), receiver.getY()) + safePadding,
                    worldMinY, worldMaxY);
            int minZ = clampToWorld((long) Math.min(start.getZ(), receiver.getZ()) - safePadding,
                    worldMinZ, worldMaxZ);
            int maxZ = clampToWorld((long) Math.max(start.getZ(), receiver.getZ()) + safePadding,
                    worldMinZ, worldMaxZ);
            boolean exhausted = minX == worldMinX && maxX == worldMaxX
                    && minY == worldMinY && maxY == worldMaxY
                    && minZ == worldMinZ && maxZ == worldMaxZ;
            return new Bounds(minX, maxX, minY, maxY, minZ, maxZ, exhausted);
        }

        private static int clampToWorld(long value, int minimum, int maximum) {
            return (int) Math.max(minimum, Math.min((long) maximum, value));
        }

    }

    static ObstacleAwareRouteSearch obstacleAwareRouteSearch(
            ClientLevel level,
            CreateMechanicalPlan.KineticEndpoint source,
            CreateMechanicalPlan.KineticEndpoint destination,
            BlockPos receiver,
            Set<Long> rejectedCells,
            int envelopePadding) {
        return new ObstacleAwareRouteSearch(
                level, source, destination, receiver, rejectedCells, envelopePadding);
    }

    record TentativeRoute(
            CreateMechanicalPlan.KineticEndpoint source,
            CreateMechanicalPlan.KineticEndpoint destination,
            BlockPos receiver,
            List<BlockPos> positions,
            String geometry,
            String routeHash) {}

    static CreateMechanicalPlan.RouteCell surveyCell(
            ClientLevel level, TentativeRoute route, int index) {
        if (index < 0 || index >= route.positions().size()) return null;
        BlockPos position = route.positions().get(index);
        BlockPos support = index == 0 ? route.source().position() : route.positions().get(index - 1);
        Direction face = CreateMechanicalPlan.between(support, position);
        if (face == null || !level.isLoaded(position) || !level.isLoaded(support)
                || !isEmptyRouteCell(level, position)) return null;
        BlockPos stand = findStand(level, position, new HashSet<>(route.positions()));
        return stand == null ? null : new CreateMechanicalPlan.RouteCell(position, support, face, stand);
    }

    static BlockPos findTravelStand(
            ClientLevel level, BlockPos around, int horizontalRadius, int verticalRadius) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int radius = 0; radius <= horizontalRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                        candidates.add(around.offset(dx, dy, dz));
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble((BlockPos position) -> position.distSqr(around))
                .thenComparingLong(BlockPos::asLong));
        for (BlockPos candidate : candidates) {
            if (standable(level, candidate)) return candidate.immutable();
        }
        return null;
    }
}
