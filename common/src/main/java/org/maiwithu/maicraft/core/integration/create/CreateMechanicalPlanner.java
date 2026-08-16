// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

/** Endpoint survey plus tick-sliced, preserve-existing mechanical route planning. */
final class CreateMechanicalPlanner {
    private static final Direction[] VERTICAL = {Direction.UP, Direction.DOWN};

    private record Candidate(CreateMechanicalPlan.KineticEndpoint endpoint, double hintDistance) {}

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
        ClientLevel level = player.clientLevel;
        Scan sourceScan = scanKinetics(level, request.source(), true);
        facts.put("source_loaded_cells", sourceScan.loadedCells);
        facts.put("source_unloaded_cells", sourceScan.unloadedCells);
        if (sourceScan.candidates.isEmpty()) {
            String code = sourceScan.unloadedCells > 0
                    ? "source_needs_exploration" : "powered_source_not_found";
            facts.put("failure_code", code);
            facts.put("detail", "no loaded kinetic endpoint with a live non-zero network and vertical shaft was observed");
            facts.put("recovery_options", List.of("travel_near_source", "inspect_source", "cancel"));
            return CreateMechanicalPlan.Result.fail(code, facts);
        }

        Scan destinationScan = scanKinetics(level, request.destination(), false);
        facts.put("destination_loaded_cells", destinationScan.loadedCells);
        facts.put("destination_unloaded_cells", destinationScan.unloadedCells);
        List<Candidate> destinationCandidates = destinationScan.candidates.stream()
                .filter(candidate -> !candidate.endpoint().network()
                        || Math.abs(candidate.endpoint().speed()) <= 0.0001f)
                .toList();
        long poweredDestinations = destinationScan.candidates.size() - destinationCandidates.size();
        if (destinationCandidates.isEmpty() && destinationScan.unloadedCells > 0) {
            facts.put("failure_code", "destination_needs_exploration");
            facts.put("detail", "part of the destination endpoint region is still unloaded, so visible powered endpoints cannot prove that no compatible destination exists");
            facts.put("recovery_options", List.of("travel_near_destination", "cancel"));
            return CreateMechanicalPlan.Result.fail("destination_needs_exploration", facts);
        }
        if (destinationCandidates.isEmpty() && poweredDestinations > 0 && !request.allowFreeReceiver()) {
            facts.put("failure_code", "destination_already_powered");
            facts.put("detail", "the visible destination is already on a live kinetic network; public facts cannot prove it is the requested source network");
            facts.put("recovery_options", List.of("inspect_existing_network", "choose_unpowered_destination", "cancel"));
            return CreateMechanicalPlan.Result.fail("destination_already_powered", facts);
        }
        if (destinationCandidates.isEmpty() && !request.allowFreeReceiver()) {
            facts.put("failure_code", "kinetic_destination_not_found");
            facts.put("detail", "no loaded unpowered kinetic endpoint with a vertical shaft was observed");
            facts.put("recovery_options", List.of("travel_near_destination", "inspect_destination", "allow_verified_free_receiver", "cancel"));
            return CreateMechanicalPlan.Result.fail("kinetic_destination_not_found", facts);
        }

        // Route planning is intentionally delegated to the progressive, tick-sliced search. A
        // synchronous exhaustive endpoint-pair/A* pass here would freeze the client precisely on
        // the large but legitimate jobs this integration is meant to support.
        facts.put("failure_code", "route_needs_exploration");
        facts.put("detail", "compatible endpoint facts are available; the preserving route now requires tick-sliced obstacle-aware investigation");
        facts.put("source_endpoint_candidates", sourceScan.candidates.size());
        facts.put("destination_endpoint_candidates", destinationCandidates.size());
        facts.put("route_search", "bounded_3d_chain_drive_state_a_star");
        facts.put("recovery_options", List.of(
                "continue_progressive_route_search", "choose_other_endpoint", "cancel"));
        return CreateMechanicalPlan.Result.fail("route_needs_exploration", facts);
    }

    private record Scan(
            List<Candidate> candidates,
            int loadedCells,
            int unloadedCells,
            BlockPos nextObservation) {}

    private static Scan scanKinetics(
            ClientLevel level, CreateMechanicalPower.Endpoint region, boolean poweredOnly) {
        List<Candidate> candidates = new ArrayList<>();
        int loaded = 0;
        int unloaded = 0;
        BlockPos nextObservation = null;
        BlockPos center = region.center();
        int radius = region.searchRadius();
        int radiusSq = radius * radius;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radiusSq) continue;
                    BlockPos position = center.offset(dx, dy, dz);
                    if (level.isOutsideBuildHeight(position)
                            || !level.getWorldBorder().isWithinBounds(position)) continue;
                    if (!level.isLoaded(position)) {
                        unloaded++;
                        nextObservation = nearerObservation(nextObservation, position, center);
                        continue;
                    }
                    loaded++;
                    CreateKineticsBridge.Facts kinetic = CreateKineticsBridge.inspect(level, position);
                    if (kinetic == null || poweredOnly && !kinetic.powered()) continue;
                    BlockState state = level.getBlockState(position);
                    for (Direction face : VERTICAL) {
                        if (!CreateKineticsBridge.hasShaftTowards(level, position, state, face)) continue;
                        BlockPos adjacent = position.relative(face);
                        if (level.isOutsideBuildHeight(adjacent)
                                || !level.getWorldBorder().isWithinBounds(adjacent)) continue;
                        if (!level.isLoaded(adjacent)) {
                            unloaded++;
                            nextObservation = nearerObservation(nextObservation, adjacent, center);
                            continue;
                        }
                        if (!isEmptyRouteCell(level, adjacent)) continue;
                        candidates.add(new Candidate(new CreateMechanicalPlan.KineticEndpoint(
                                position, state, face, kinetic.speed(), kinetic.hasNetwork()),
                                position.distSqr(center)));
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::hintDistance)
                .thenComparingLong(c -> c.endpoint().position().asLong())
                .thenComparingInt(c -> c.endpoint().shaftFace().ordinal()));
        return new Scan(List.copyOf(candidates), loaded, unloaded, nextObservation);
    }

    private static BlockPos nearerObservation(BlockPos current, BlockPos candidate, BlockPos center) {
        BlockPos frozen = candidate.immutable();
        if (current == null) return frozen;
        double candidateDistance = horizontalDistanceSq(candidate, center);
        double currentDistance = horizontalDistanceSq(current, center);
        if (candidateDistance < currentDistance
                || candidateDistance == currentDistance && candidate.asLong() < current.asLong()) {
            return frozen;
        }
        return current;
    }

    private static double horizontalDistanceSq(BlockPos left, BlockPos right) {
        double dx = left.getX() - right.getX();
        double dz = left.getZ() - right.getZ();
        return dx * dx + dz * dz;
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

    private static List<BlockPos> freeReceivers(
            ClientLevel level, CreateMechanicalPower.Endpoint destination) {
        List<BlockPos> result = new ArrayList<>();
        BlockPos center = destination.center();
        int radius = destination.searchRadius();
        int radiusSq = radius * radius;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radiusSq) continue;
                    BlockPos position = center.offset(dx, dy, dz);
                    if (!level.isLoaded(position) || !isEmptyRouteCell(level, position)) continue;
                    if (level.getBlockEntity(position.below()) != null) continue;
                    if (protectedTerrain(level, position.below())) continue;
                    result.add(position.immutable());
                }
            }
        }
        result.sort(Comparator.comparingDouble((BlockPos position) -> position.distSqr(center))
                .thenComparingLong(BlockPos::asLong));
        return List.copyOf(result);
    }

    static boolean isEmptyRouteCell(ClientLevel level, BlockPos position) {
        if (!level.isLoaded(position) || level.isOutsideBuildHeight(position)
                || !level.getWorldBorder().isWithinBounds(position)) return false;
        BlockState state = level.getBlockState(position);
        if (!state.isAir() || !state.getFluidState().isEmpty()
                || level.getBlockEntity(position) != null) return false;
        return !protectedTerrain(level, position.below());
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

    // ---------------------------------------------------------------------
    // Progressive loaded-world survey seam
    // ---------------------------------------------------------------------

    record EndpointSurvey(
            List<CreateMechanicalPlan.KineticEndpoint> endpoints,
            int loadedCells,
            int unloadedCells,
            int poweredEndpoints,
            BlockPos nextObservation) {}

    static EndpointSurvey surveyEndpoint(
            ClientLevel level, CreateMechanicalPower.Endpoint region, boolean poweredOnly) {
        Scan scan = scanKinetics(level, region, poweredOnly);
        List<CreateMechanicalPlan.KineticEndpoint> endpoints = scan.candidates().stream()
                .map(Candidate::endpoint).toList();
        int powered = (int) endpoints.stream().filter(endpoint -> endpoint.network()
                && Math.abs(endpoint.speed()) > 0.0001f).count();
        return new EndpointSurvey(endpoints, scan.loadedCells(), scan.unloadedCells(), powered,
                scan.nextObservation());
    }

    static List<BlockPos> surveyFreeReceivers(
            ClientLevel level, CreateMechanicalPower.Endpoint destination) {
        return freeReceivers(level, destination);
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
     * <p>The graph is finite from the two user-supplied endpoint regions in X/Y/Z, additionally
     * bounded by build height and the world border. Loaded transitions verify target emptiness,
     * support continuity, and at least one first-person placement stance. Unloaded transitions are
     * provisional and must pass the progressive physical survey. There is no wall-clock,
     * attempt-count, or total-expansion cutoff.</p>
     */
    static final class ObstacleAwareRouteSearch {
        private enum SearchStep { RUNNING, FOUND, EXHAUSTED }

        private record Bounds(
                int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
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
                CreateMechanicalPower.Request request,
                CreateMechanicalPlan.KineticEndpoint source,
                CreateMechanicalPlan.KineticEndpoint destination,
                BlockPos receiver,
                Set<Long> rejectedCells) {
            this.level = level;
            this.source = source;
            this.destination = destination;
            this.receiver = receiver.immutable();
            this.start = source.position().relative(source.shaftFace()).immutable();
            this.rejectedCells = Set.copyOf(rejectedCells);
            this.bounds = bounds(request, start, receiver);
            if (startPlacementAllowed()) {
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
                CreateMechanicalPower.Request request, BlockPos start, BlockPos receiver) {
            CreateMechanicalPower.Endpoint source = request.source();
            CreateMechanicalPower.Endpoint destination = request.destination();
            int minX = Math.min(Math.min(source.center().getX() - source.searchRadius(),
                            destination.center().getX() - destination.searchRadius()),
                    Math.min(start.getX(), receiver.getX()));
            int maxX = Math.max(Math.max(source.center().getX() + source.searchRadius(),
                            destination.center().getX() + destination.searchRadius()),
                    Math.max(start.getX(), receiver.getX()));
            int minY = Math.min(Math.min(source.center().getY() - source.searchRadius(),
                            destination.center().getY() - destination.searchRadius()),
                    Math.min(start.getY(), receiver.getY()));
            int maxY = Math.max(Math.max(source.center().getY() + source.searchRadius(),
                            destination.center().getY() + destination.searchRadius()),
                    Math.max(start.getY(), receiver.getY()));
            int minZ = Math.min(Math.min(source.center().getZ() - source.searchRadius(),
                            destination.center().getZ() - destination.searchRadius()),
                    Math.min(start.getZ(), receiver.getZ()));
            int maxZ = Math.max(Math.max(source.center().getZ() + source.searchRadius(),
                            destination.center().getZ() + destination.searchRadius()),
                    Math.max(start.getZ(), receiver.getZ()));
            return new Bounds(minX, maxX, minY, maxY, minZ, maxZ);
        }
    }

    static ObstacleAwareRouteSearch obstacleAwareRouteSearch(
            ClientLevel level,
            CreateMechanicalPower.Request request,
            CreateMechanicalPlan.KineticEndpoint source,
            CreateMechanicalPlan.KineticEndpoint destination,
            BlockPos receiver,
            Set<Long> rejectedCells) {
        return new ObstacleAwareRouteSearch(
                level, request, source, destination, receiver, rejectedCells);
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
