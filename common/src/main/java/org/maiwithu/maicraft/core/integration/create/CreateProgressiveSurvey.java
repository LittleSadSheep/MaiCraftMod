// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;

/**
 * Bounded progressive observation: load source, load destination, walk the proposed corridor from
 * destination back to source, and approve it only after every cell and placement stance was seen.
 */
final class CreateProgressiveSurvey {
    enum Status { RUNNING, READY, FAILED }
    private enum Phase { SOURCE, DESTINATION, CORRIDOR, RETURN_SOURCE, READY, FAILED }

    record Failure(String code, String detail, FailureType type, List<String> recovery) {}

    private static final int CELLS_PER_TICK = 48;
    private static final int MAX_TRAVEL_FAILURES = 5;

    private final CreateMechanicalPower.Request request;
    private final Travel travel = new Travel();
    private Phase phase = Phase.SOURCE;
    private CreateMechanicalPlan.KineticEndpoint source;
    private CreateMechanicalPlan.KineticEndpoint destination;
    private BlockPos receiver;
    private CreateMechanicalPlanner.TentativeRoute route;
    private List<CreateMechanicalPlanner.TentativeRoute> routeCandidates = List.of();
    private int routeCandidateIndex;
    private List<CreateMechanicalPlan.RouteCell> surveyed;
    private Set<BlockPos> routeSet;
    private int corridorIndex = -1;
    private CreateMechanicalPlan plan;
    private Failure failure;
    private int sourceLoadedCells;
    private int sourceUnloadedCells;
    private int destinationLoadedCells;
    private int destinationUnloadedCells;
    private int travelSegments;

    CreateProgressiveSurvey(CreateMechanicalPower.Request request) {
        this.request = request;
    }

    Status tick(LocalPlayerContext context) {
        if (phase == Phase.READY) return Status.READY;
        if (phase == Phase.FAILED) return Status.FAILED;
        return switch (phase) {
            case SOURCE -> surveySource(context);
            case DESTINATION -> surveyDestination(context);
            case CORRIDOR -> surveyCorridor(context);
            case RETURN_SOURCE -> returnSource(context);
            default -> Status.FAILED;
        };
    }

    CreateMechanicalPlan plan() { return plan; }
    Failure failure() { return failure; }
    int travelSegments() { return travelSegments; }
    int sourceLoadedCells() { return sourceLoadedCells; }
    int sourceUnloadedCells() { return sourceUnloadedCells; }
    int destinationLoadedCells() { return destinationLoadedCells; }
    int destinationUnloadedCells() { return destinationUnloadedCells; }
    int rejectedRouteCandidates() { return routeCandidateIndex; }

    void pause() { travel.pause(); }
    void stop() { travel.stop(); }

    private Status surveySource(LocalPlayerContext context) {
        CreateMechanicalPlanner.EndpointSurvey survey = CreateMechanicalPlanner.surveyEndpoint(
                context.level(), request.source(), true);
        sourceLoadedCells = survey.loadedCells();
        sourceUnloadedCells = survey.unloadedCells();
        if (!survey.endpoints().isEmpty()) {
            source = survey.endpoints().stream()
                    .min(Comparator.comparingDouble(endpoint ->
                            endpoint.position().distSqr(request.source().center())))
                    .orElseThrow();
            travel.stop();
            phase = Phase.DESTINATION;
            return Status.RUNNING;
        }
        if (survey.unloadedCells() == 0) {
            return fail("powered_source_not_found",
                    "the fully observed source region contains no live non-zero-speed vertical-shaft endpoint",
                    FailureType.TARGET_LOST,
                    List.of("restore_source_power", "choose_other_source", "cancel"));
        }
        BlockPos observation = survey.nextObservation() == null
                ? request.source().center() : survey.nextObservation();
        return travelToward(context, observation, 2,
                "source_unreachable", "could not reach a loaded observation position for the source region");
    }

    private Status surveyDestination(LocalPlayerContext context) {
        CreateMechanicalPlanner.EndpointSurvey survey = CreateMechanicalPlanner.surveyEndpoint(
                context.level(), request.destination(), false);
        destinationLoadedCells = survey.loadedCells();
        destinationUnloadedCells = survey.unloadedCells();
        List<CreateMechanicalPlan.KineticEndpoint> unpowered = survey.endpoints().stream()
                .filter(endpoint -> !endpoint.network() || Math.abs(endpoint.speed()) <= 0.0001f)
                .sorted(Comparator.comparingDouble(endpoint ->
                        endpoint.position().distSqr(request.destination().center())))
                .toList();
        if (!unpowered.isEmpty()) {
            destination = unpowered.get(0);
            receiver = destination.position().relative(destination.shaftFace());
            return beginCorridor(context.level());
        }
        if (request.allowFreeReceiver()) {
            List<BlockPos> free = CreateMechanicalPlanner.surveyFreeReceivers(
                    context.level(), request.destination());
            if (!free.isEmpty()) {
                destination = null;
                receiver = free.get(0);
                return beginCorridor(context.level());
            }
        }
        if (survey.unloadedCells() == 0) {
            if (survey.poweredEndpoints() > 0) {
                return fail("destination_already_powered",
                        "the fully observed destination region has only already-powered endpoints, so requested source membership is not provable",
                        FailureType.TARGET_LOST,
                        List.of("inspect_existing_network", "choose_unpowered_destination", "cancel"));
            }
            return fail("kinetic_destination_not_found",
                    "the fully observed destination region contains no compatible unpowered endpoint or approved free receiver",
                    FailureType.TARGET_LOST,
                    List.of("place_destination_machine", "allow_verified_free_receiver", "cancel"));
        }
        BlockPos observation = survey.nextObservation() == null
                ? request.destination().center() : survey.nextObservation();
        return travelToward(context, observation, 2,
                "destination_unreachable",
                "could not reach a loaded observation position for the destination region");
    }

    private Status beginCorridor(ClientLevel level) {
        travel.stop();
        routeCandidates = CreateMechanicalPlanner.tentativeRoutes(
                level, source, destination, receiver);
        if (routeCandidates.isEmpty()) {
            return fail("progressive_route_unbounded",
                    "no bounded at-most-five-run geometry connects the surveyed endpoints",
                    FailureType.NO_PATH,
                    List.of("choose_closer_endpoint", "choose_other_endpoint", "cancel"));
        }
        routeCandidateIndex = 0;
        selectRoute(routeCandidates.get(routeCandidateIndex));
        phase = Phase.CORRIDOR;
        return Status.RUNNING;
    }

    private void selectRoute(CreateMechanicalPlanner.TentativeRoute selected) {
        route = selected;
        surveyed = new ArrayList<>(java.util.Collections.nCopies(route.positions().size(), null));
        routeSet = Set.copyOf(new HashSet<>(route.positions()));
        corridorIndex = route.positions().size() - 1;
    }

    private Status surveyCorridor(LocalPlayerContext context) {
        ClientLevel level = context.level();
        int budget = CELLS_PER_TICK;
        while (corridorIndex >= 0 && budget-- > 0) {
            BlockPos position = route.positions().get(corridorIndex);
            BlockPos support = corridorIndex == 0
                    ? source.position() : route.positions().get(corridorIndex - 1);
            if (!level.isLoaded(position) || !level.isLoaded(support)) {
                // Move a short distance INTO the unsurveyed prefix. Walking merely to the
                // already-loaded boundary cell can leave its adjacent support across the chunk
                // edge unloaded forever.
                BlockPos observationTarget = route.positions().get(Math.max(0, corridorIndex - 16));
                return travelToward(context, observationTarget, 2,
                        "corridor_unreachable",
                        "could not load a continuous observation window along the proposed mechanical corridor");
            }
            CreateMechanicalPlan.RouteCell cell = surveyCell(level, corridorIndex);
            if (cell == null) {
                String detail = CreateMechanicalPlanner.isEmptyRouteCell(level, position)
                        ? "a proposed route cell has no loaded safe first-person placement stance"
                        : "a proposed route cell is occupied, hazardous, protected, or outside the preserving route contract";
                if (routeCandidateIndex + 1 < routeCandidates.size()) {
                    routeCandidateIndex++;
                    travel.stop();
                    selectRoute(routeCandidates.get(routeCandidateIndex));
                    return Status.RUNNING;
                }
                return fail("progressive_corridor_rejected", detail,
                        FailureType.NO_PATH,
                        List.of("move_obstruction", "choose_other_endpoint", "cancel"));
            }
            surveyed.set(corridorIndex, cell);
            corridorIndex--;
        }
        if (corridorIndex >= 0) return Status.RUNNING;
        travel.stop();
        phase = Phase.RETURN_SOURCE;
        return Status.RUNNING;
    }

    private CreateMechanicalPlan.RouteCell surveyCell(ClientLevel level, int index) {
        BlockPos position = route.positions().get(index);
        BlockPos support = index == 0 ? source.position() : route.positions().get(index - 1);
        net.minecraft.core.Direction face = CreateMechanicalPlan.between(support, position);
        if (face == null || !CreateMechanicalPlanner.isEmptyRouteCell(level, position)) return null;
        BlockPos stand = findStandForRoute(level, position);
        return stand == null ? null
                : new CreateMechanicalPlan.RouteCell(position, support, face, stand);
    }

    private BlockPos findStandForRoute(ClientLevel level, BlockPos position) {
        // Reuse the planner's conservative body geometry, then reject any stand occupied by a
        // future route cell. Expanding shells preserve deterministic choice.
        for (int radius = 1; radius <= 4; radius++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                        BlockPos probe = position.offset(dx, dy, dz);
                        if (routeSet.contains(probe) || routeSet.contains(probe.above())) continue;
                        BlockPos stand = CreateMechanicalPlanner.findTravelStand(level, probe, 0, 0);
                        if (stand != null && steepEnough(stand, position)) return stand;
                    }
                }
            }
        }
        return null;
    }

    private static boolean steepEnough(BlockPos stand, BlockPos target) {
        double dx = target.getX() + 0.5 - (stand.getX() + 0.5);
        double dz = target.getZ() + 0.5 - (stand.getZ() + 0.5);
        double dy = target.getY() + 0.5 - (stand.getY() + 1.62);
        double pitch = Math.toDegrees(Math.atan2(Math.abs(dy), Math.sqrt(dx * dx + dz * dz)));
        double reachSq = dx * dx + dy * dy + dz * dz;
        return pitch >= 48.0 && reachSq <= 4.35 * 4.35;
    }

    private Status returnSource(LocalPlayerContext context) {
        BlockPos returnTarget = surveyed.get(0).stand();
        if (context.player().blockPosition().distSqr(returnTarget) > 4.0) {
            return travelToward(context, returnTarget, 1,
                    "return_source_unreachable",
                    "the surveyed corridor could not be followed back to its construction start");
        }
        CreateKineticsBridge.Facts live = CreateKineticsBridge.inspect(
                context.level(), source.position());
        if (live == null || !live.powered()
                || !context.level().getBlockState(source.position()).equals(source.state())) {
            return fail("source_changed",
                    "the source changed before progressive construction could begin",
                    FailureType.TARGET_LOST,
                    List.of("restore_source_power", "resurvey", "cancel"));
        }
        int window = Math.min(64, surveyed.size());
        for (int i = 0; i < window; i++) {
            BlockPos position = surveyed.get(i).position();
            if (!context.level().isLoaded(position)
                    || !CreateMechanicalPlanner.isEmptyRouteCell(context.level(), position)) {
                return fail("corridor_changed",
                        "the source-side surveyed corridor changed before construction began",
                        FailureType.TARGET_LOST,
                        List.of("resurvey", "move_obstruction", "cancel"));
            }
        }
        String digest = surveyDigest(route, surveyed);
        plan = new CreateMechanicalPlan(source, destination, receiver, List.copyOf(surveyed),
                route.geometry(), route.routeHash(), true, live.speed(), false);
        phase = Phase.READY;
        // The digest is private execution evidence reported separately; the semantic caller never
        // receives individual route cells.
        surveyDigest = digest;
        return Status.READY;
    }

    private String surveyDigest;
    String surveyDigest() { return surveyDigest; }

    private Status travelToward(
            LocalPlayerContext context,
            BlockPos target,
            int radius,
            String code,
            String detail) {
        Travel.Status status = travel.tick(context.player(), target, radius);
        if (status == Travel.Status.RUNNING) return Status.RUNNING;
        if (status == Travel.Status.ARRIVED) {
            travelSegments++;
            return Status.RUNNING;
        }
        return fail(code, detail + ": " + travel.failure(), FailureType.NO_PATH,
                List.of("make_path_accessible", "choose_other_endpoint", "cancel"));
    }

    private Status fail(String code, String detail, FailureType type, List<String> recovery) {
        travel.stop();
        failure = new Failure(code, detail, type, List.copyOf(recovery));
        phase = Phase.FAILED;
        return Status.FAILED;
    }

    private static String surveyDigest(
            CreateMechanicalPlanner.TentativeRoute route,
            List<CreateMechanicalPlan.RouteCell> cells) {
        StringBuilder value = new StringBuilder(route.routeHash());
        for (CreateMechanicalPlan.RouteCell cell : cells) {
            value.append('|').append(cell.position().asLong())
                    .append(':').append(cell.stand().asLong()).append(":air");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Incremental loaded-frontier navigation used by survey and progressive construction. */
    static final class Travel {
        enum Status { RUNNING, ARRIVED, FAILED }

        private PlayerNav nav;
        private BlockPos semanticTarget;
        private BlockPos waypoint;
        private int failures;
        private final Set<Long> deniedWaypoints = new HashSet<>();
        private String failure = "no loaded waypoint was found";

        Status tick(LocalPlayer player, BlockPos target, int radius) {
            if (semanticTarget == null || !semanticTarget.equals(target)) {
                stop();
                semanticTarget = target.immutable();
                failures = 0;
            }
            if (horizontalDistanceSq(player.blockPosition(), target) <= radius * radius
                    && player.level().isLoaded(target)) {
                stop();
                return Status.ARRIVED;
            }
            if (nav == null) {
                waypoint = chooseWaypoint(player, target);
                if (waypoint == null) {
                    failure = "the loaded frontier has no standable position toward the current semantic target";
                    return Status.FAILED;
                }
                BlockPos frozen = waypoint;
                nav = new PlayerNav(player, frozen, 0.9,
                        () -> player.blockPosition().distSqr(frozen) <= 1.0);
            }
            PlayerNav.Status status = nav.tick();
            if (status == PlayerNav.Status.RUNNING) return Status.RUNNING;
            if (status == PlayerNav.Status.ARRIVED) {
                nav.stop();
                nav = null;
                return Status.RUNNING;
            }
            failure = nav.failReason();
            if (waypoint != null) deniedWaypoints.add(waypoint.asLong());
            nav.stop();
            nav = null;
            if (++failures >= MAX_TRAVEL_FAILURES) return Status.FAILED;
            return Status.RUNNING;
        }

        String failure() { return failure; }

        void pause() {
            if (nav != null) nav.pause();
        }

        void stop() {
            if (nav != null) {
                try { nav.stop(); } catch (RuntimeException ignored) { }
            }
            nav = null;
            waypoint = null;
            semanticTarget = null;
            deniedWaypoints.clear();
        }

        private BlockPos chooseWaypoint(LocalPlayer player, BlockPos target) {
            ClientLevel level = player.clientLevel;
            BlockPos from = player.blockPosition();
            double dx = target.getX() - from.getX();
            double dz = target.getZ() - from.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length < 1.0) {
                return CreateMechanicalPlanner.findTravelStand(level, target, 6, 16);
            }
            double nx = dx / length;
            double nz = dz / length;
            double px = -nz;
            double pz = nx;
            for (int distance : new int[]{28, 22, 16, 10, 6}) {
                for (int lateral : new int[]{0, 6, -6, 12, -12}) {
                    int x = MthFloor(from.getX() + nx * Math.min(distance, length) + px * lateral);
                    int z = MthFloor(from.getZ() + nz * Math.min(distance, length) + pz * lateral);
                    BlockPos probe = new BlockPos(x, from.getY(), z);
                    if (!level.isLoaded(probe)) continue;
                    BlockPos stand = CreateMechanicalPlanner.findTravelStand(level, probe, 4, 16);
                    if (stand != null && stand.distSqr(from) > 4.0
                            && !deniedWaypoints.contains(stand.asLong())) return stand;
                }
            }
            return null;
        }

        private static int MthFloor(double value) {
            return net.minecraft.util.Mth.floor(value);
        }

        private static double horizontalDistanceSq(BlockPos a, BlockPos b) {
            double dx = a.getX() - b.getX();
            double dz = a.getZ() - b.getZ();
            return dx * dx + dz * dz;
        }
    }
}
