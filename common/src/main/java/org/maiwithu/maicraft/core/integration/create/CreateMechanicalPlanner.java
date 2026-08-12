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
