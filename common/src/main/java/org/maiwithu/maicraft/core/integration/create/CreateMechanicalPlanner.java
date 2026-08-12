// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;

/** Loaded-only endpoint survey and deterministic, preserve-existing route planning. */
final class CreateMechanicalPlanner {
    private static final int MAX_ENDPOINT_CANDIDATES = 32;
    private static final int MAX_LAYER_OFFSET = 12;
    private static final int MAX_ROUTE_RUNS = 5;
    private static final int MAX_ENDPOINT_PAIR_ATTEMPTS = 96;
    private static final Direction[] VERTICAL = {Direction.UP, Direction.DOWN};

    private record Candidate(CreateMechanicalPlan.KineticEndpoint endpoint, double hintDistance) {}
    private record RouteCandidate(
            CreateMechanicalPlan.KineticEndpoint source,
            CreateMechanicalPlan.KineticEndpoint destination,
            BlockPos receiver,
            List<BlockPos> positions,
            int unloadedCells,
            int score) {}

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
        if (destinationCandidates.isEmpty() && poweredDestinations > 0 && !request.allowFreeReceiver()) {
            facts.put("failure_code", "destination_already_powered");
            facts.put("detail", "the visible destination is already on a live kinetic network; public facts cannot prove it is the requested source network");
            facts.put("recovery_options", List.of("inspect_existing_network", "choose_unpowered_destination", "cancel"));
            return CreateMechanicalPlan.Result.fail("destination_already_powered", facts);
        }
        if (destinationCandidates.isEmpty() && !request.allowFreeReceiver()) {
            String code = destinationScan.unloadedCells > 0
                    ? "destination_needs_exploration" : "kinetic_destination_not_found";
            facts.put("failure_code", code);
            facts.put("detail", "no loaded unpowered kinetic endpoint with a vertical shaft was observed");
            facts.put("recovery_options", List.of("travel_near_destination", "inspect_destination", "allow_verified_free_receiver", "cancel"));
            return CreateMechanicalPlan.Result.fail(code, facts);
        }

        RouteSearch routeSearch = findBestRoute(player, request, sourceScan.candidates,
                destinationCandidates);
        if (routeSearch.best == null) {
            String code = routeSearch.unloadedCandidates > 0
                    ? "route_needs_exploration" : "no_preserving_route";
            facts.put("failure_code", code);
            facts.put("detail", "no loaded preserving chain-cell route with a safe pathing-compatible placement corridor and at most five axis runs was verified");
            facts.put("blocked_route_candidates", routeSearch.blockedCandidates);
            facts.put("unloaded_route_candidates", routeSearch.unloadedCandidates);
            facts.put("recovery_options", List.of("travel_to_load_corridor", "choose_other_endpoint", "move_obstruction", "cancel"));
            return CreateMechanicalPlan.Result.fail(code, facts);
        }

        RouteCandidate candidate = routeSearch.best;
        List<CreateMechanicalPlan.RouteCell> cells = materializeCells(level, candidate);
        if (cells == null) {
            facts.put("failure_code", "placement_stance_unavailable");
            facts.put("detail", "the route target cells preserve existing blocks, but at least one cell has no safe reachable first-person placement stance");
            facts.put("recovery_options", List.of("choose_other_endpoint", "make_corridor_accessible", "cancel"));
            return CreateMechanicalPlan.Result.fail("placement_stance_unavailable", facts);
        }
        String geometry = CreateMechanicalPlan.geometry(candidate.positions);
        String routeHash = CreateMechanicalPlan.hashRoute(candidate.positions, geometry);
        CreateMechanicalPlan plan = new CreateMechanicalPlan(
                candidate.source,
                candidate.destination,
                candidate.receiver,
                cells,
                geometry,
                routeHash,
                false,
                candidate.source.speed(),
                candidate.destination != null && candidate.destination.network()
                        && Math.abs(candidate.destination.speed()) > 0.0001f);
        facts.put("source", candidate.source.position().toShortString());
        facts.put("source_speed", candidate.source.speed());
        facts.put("destination", plan.destinationPosition().toShortString());
        facts.put("receiver", candidate.receiver.toShortString());
        facts.put("delivery_kind", candidate.destination == null ? "verified_free_receiver" : "kinetic_machine");
        facts.put("route_cells", cells.size());
        facts.put("route_runs", countRuns(candidate.positions));
        facts.put("route_hash", routeHash);
        facts.put("transmission", request.transmission() == CreateMechanicalPower.Transmission.AUTO
                ? "auto_selected_encased_chain_drive" : "encased_chain_drive");
        facts.put("numeric_stress_margin_supported", false);
        facts.put("stress_evidence",
                "live network presence, non-zero speed, and optional boolean overstress only; numeric capacity/impact margin is not a stable reflected contract");
        facts.put("preserve_existing", true);
        facts.put("terrain_mutation", false);
        facts.put("route_layer_strategy", "endpoint_layers_then_loaded_surface_samples");
        facts.put("placement_corridor", "shared_pathing_walkability_without_digging_or_support_building");
        return CreateMechanicalPlan.Result.ok(plan, facts);
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
        if (candidates.size() > MAX_ENDPOINT_CANDIDATES) {
            candidates = new ArrayList<>(candidates.subList(0, MAX_ENDPOINT_CANDIDATES));
        }
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

    private record RouteSearch(RouteCandidate best, int blockedCandidates, int unloadedCandidates) {}

    private static RouteSearch findBestRoute(
            LocalPlayer player,
            CreateMechanicalPower.Request request,
            List<Candidate> sources,
            List<Candidate> destinations) {
        ClientLevel level = player.clientLevel;
        RouteCandidate best = null;
        int blocked = 0;
        int unloaded = 0;
        List<CreateMechanicalPlan.KineticEndpoint> destinationEndpoints = destinations.stream()
                .map(Candidate::endpoint).toList();
        List<BlockPos> freeReceivers = destinationEndpoints.isEmpty() && request.allowFreeReceiver()
                ? freeReceivers(level, request.destination()) : List.of();
        int endpointAttempts = 0;
        boolean exhausted = false;
        for (Candidate sourceCandidate : sources) {
            CreateMechanicalPlan.KineticEndpoint source = sourceCandidate.endpoint();
            if (!destinationEndpoints.isEmpty()) {
                for (CreateMechanicalPlan.KineticEndpoint destination : destinationEndpoints) {
                    if (endpointAttempts++ >= MAX_ENDPOINT_PAIR_ATTEMPTS) {
                        exhausted = true;
                        break;
                    }
                    RouteSearchOne search = routeBetween(level, source, destination,
                            destination.position().relative(destination.shaftFace()));
                    blocked += search.blocked;
                    unloaded += search.unloaded;
                    if (search.best != null && (best == null || search.best.score < best.score)) {
                        best = search.best;
                    }
                }
            } else {
                for (BlockPos receiver : freeReceivers) {
                    if (endpointAttempts++ >= MAX_ENDPOINT_PAIR_ATTEMPTS) {
                        exhausted = true;
                        break;
                    }
                    RouteSearchOne search = routeBetween(level, source, null, receiver);
                    blocked += search.blocked;
                    unloaded += search.unloaded;
                    if (search.best != null && (best == null || search.best.score < best.score)) {
                        best = search.best;
                    }
                }
            }
            if (exhausted) break;
        }
        return new RouteSearch(best, blocked, unloaded);
    }

    private record RouteSearchOne(RouteCandidate best, int blocked, int unloaded) {}

    private static RouteSearchOne routeBetween(
            ClientLevel level,
            CreateMechanicalPlan.KineticEndpoint source,
            CreateMechanicalPlan.KineticEndpoint destination,
            BlockPos receiver) {
        BlockPos start = source.position().relative(source.shaftFace());
        RouteCandidate best = null;
        int blocked = 0;
        int unloaded = 0;
        if (start.getX() == receiver.getX() || start.getZ() == receiver.getZ()) {
            List<BlockPos> direct = alignedRoute(start, receiver);
            int missing = 0;
            boolean unsafe = direct.isEmpty() || hasDuplicate(direct);
            for (BlockPos position : direct) {
                if (!level.isLoaded(position)) missing++;
                else if (!isEmptyRouteCell(level, position)) unsafe = true;
            }
            if (missing > 0) {
                unloaded++;
            } else if (unsafe) {
                blocked++;
            } else {
                RouteCandidate directCandidate = new RouteCandidate(source, destination, receiver,
                        List.copyOf(direct), 0, direct.size() * 32);
                if (materializeCells(level, directCandidate) == null) blocked++;
                else best = directCandidate;
            }
        }
        int base = Math.max(start.getY(), receiver.getY());
        layerSearch:
        for (int layer : candidateLayers(level, start, receiver)) {
            for (boolean xFirst : new boolean[]{true, false}) {
                for (int pivot : new int[]{1, -1}) {
                    List<BlockPos> route = fiveRunRoute(start, receiver, layer, xFirst, pivot);
                    if (route.isEmpty() || countRuns(route) > MAX_ROUTE_RUNS
                            || hasDuplicate(route)) {
                        blocked++;
                        continue;
                    }
                    int missing = 0;
                    boolean unsafe = false;
                    for (BlockPos position : route) {
                        if (!level.isLoaded(position)) {
                            missing++;
                        } else if (!isEmptyRouteCell(level, position)) {
                            unsafe = true;
                            break;
                        }
                    }
                    if (missing > 0) {
                        unloaded++;
                        continue;
                    }
                    if (unsafe) {
                        blocked++;
                        continue;
                    }
                    int score = route.size() * 32 + Math.abs(layer - base) * 4
                            + (xFirst ? 0 : 1) + (pivot > 0 ? 0 : 2);
                    RouteCandidate candidate = new RouteCandidate(source, destination, receiver,
                            List.copyOf(route), 0, score);
                    if (materializeCells(level, candidate) == null) {
                        blocked++;
                    } else if (best == null || candidate.score < best.score) {
                        best = candidate;
                    }
                }
            }
            // Layers are ordered by construction cost. Once this layer has a preserving route,
            // later layers can only add vertical material and are not a better semantic choice.
            if (best != null) break layerSearch;
        }
        return new RouteSearchOne(best, blocked, unloaded);
    }

    /** Direct route for endpoints sharing an X or Z axis; no artificial pivot/backtrack. */
    private static List<BlockPos> alignedRoute(BlockPos start, BlockPos target) {
        List<BlockPos> route = new ArrayList<>();
        appendLine(route, start);
        appendLine(route, new BlockPos(start.getX(), target.getY(), start.getZ()));
        appendLine(route, target);
        return route;
    }

    private static List<BlockPos> fiveRunRoute(
            BlockPos start, BlockPos target, int layer, boolean xFirst, int pivot) {
        List<BlockPos> route = new ArrayList<>();
        appendLine(route, start);
        BlockPos atLayer = new BlockPos(start.getX(), layer, start.getZ());
        appendLine(route, atLayer);
        if (xFirst) {
            BlockPos firstAxis = new BlockPos(target.getX(), layer, start.getZ());
            appendLine(route, firstAxis);
            int pivotY = layer + pivot;
            BlockPos pivotPoint = new BlockPos(target.getX(), pivotY, start.getZ());
            appendLine(route, pivotPoint);
            BlockPos secondAxis = new BlockPos(target.getX(), pivotY, target.getZ());
            appendLine(route, secondAxis);
            appendLine(route, target);
        } else {
            BlockPos firstAxis = new BlockPos(start.getX(), layer, target.getZ());
            appendLine(route, firstAxis);
            int pivotY = layer + pivot;
            BlockPos pivotPoint = new BlockPos(start.getX(), pivotY, target.getZ());
            appendLine(route, pivotPoint);
            BlockPos secondAxis = new BlockPos(target.getX(), pivotY, target.getZ());
            appendLine(route, secondAxis);
            appendLine(route, target);
        }
        return route;
    }

    /** Append a Manhattan line from the current tail to target, excluding a duplicate tail. */
    private static void appendLine(List<BlockPos> route, BlockPos target) {
        if (route.isEmpty()) {
            route.add(target.immutable());
            return;
        }
        BlockPos cursor = route.get(route.size() - 1);
        while (!cursor.equals(target)) {
            int dx = Integer.compare(target.getX(), cursor.getX());
            int dy = dx == 0 ? Integer.compare(target.getY(), cursor.getY()) : 0;
            int dz = dx == 0 && dy == 0 ? Integer.compare(target.getZ(), cursor.getZ()) : 0;
            cursor = cursor.offset(dx, dy, dz);
            route.add(cursor.immutable());
        }
    }

    private static List<CreateMechanicalPlan.RouteCell> materializeCells(
            ClientLevel level, RouteCandidate candidate) {
        Set<BlockPos> routeSet = new HashSet<>(candidate.positions);
        List<CreateMechanicalPlan.RouteCell> result = new ArrayList<>(candidate.positions.size());
        BlockPos prior = candidate.source.position();
        for (BlockPos position : candidate.positions) {
            Direction face = CreateMechanicalPlan.between(prior, position);
            if (face == null) return null;
            BlockPos stand = findStand(level, position, routeSet);
            if (stand == null) return null;
            result.add(new CreateMechanicalPlan.RouteCell(position, prior, face, stand));
            prior = position;
        }
        return List.copyOf(result);
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
            if (standable(level, feet) && withinPlacementReach(feet, target)) return feet.immutable();
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

    private static List<BlockPos> freeReceivers(
            ClientLevel level, CreateMechanicalPower.Endpoint destination) {
        List<BlockPos> result = new ArrayList<>();
        BlockPos center = destination.center();
        int radius = Math.min(8, destination.searchRadius());
        for (int dy = -Math.min(radius, 4); dy <= Math.min(radius, 4); dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
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
        return result.size() <= 12 ? List.copyOf(result) : List.copyOf(result.subList(0, 12));
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

    private static int countRuns(List<BlockPos> route) {
        int runs = 0;
        Direction prior = null;
        for (int i = 1; i < route.size(); i++) {
            Direction now = CreateMechanicalPlan.between(route.get(i - 1), route.get(i));
            if (now == null) return Integer.MAX_VALUE;
            if (now != prior) runs++;
            prior = now;
        }
        return Math.max(1, runs);
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

    record TentativeRoute(
            CreateMechanicalPlan.KineticEndpoint source,
            CreateMechanicalPlan.KineticEndpoint destination,
            BlockPos receiver,
            List<BlockPos> positions,
            String geometry,
            String routeHash,
            String failureCode,
            String detail) {
        boolean ready() { return failureCode == null; }
    }

    /**
     * Geometry only. Cells may be unloaded and are not approved here; the progressive survey must
     * walk the entire candidate, verify every cell/stance, and return to the source before use.
     */
    static TentativeRoute tentativeRoute(
            ClientLevel level,
            CreateMechanicalPlan.KineticEndpoint source,
            CreateMechanicalPlan.KineticEndpoint destination,
            BlockPos receiver) {
        List<TentativeRoute> routes = tentativeRoutes(level, source, destination, receiver);
        return routes.isEmpty()
                ? new TentativeRoute(source, destination, receiver, List.of(), null, null,
                        "progressive_route_unbounded",
                        "no bounded at-most-five-run geometry connects the surveyed endpoints")
                : routes.get(0);
    }

    static List<TentativeRoute> tentativeRoutes(
            ClientLevel level,
            CreateMechanicalPlan.KineticEndpoint source,
            CreateMechanicalPlan.KineticEndpoint destination,
            BlockPos receiver) {
        BlockPos start = source.position().relative(source.shaftFace());
        List<List<BlockPos>> candidates = new ArrayList<>();
        if (start.getX() == receiver.getX() || start.getZ() == receiver.getZ()) {
            candidates.add(alignedRoute(start, receiver));
        }
        for (int layer : candidateLayers(level, start, receiver)) {
            for (boolean xFirst : new boolean[]{true, false}) {
                for (int pivot : new int[]{1, -1}) {
