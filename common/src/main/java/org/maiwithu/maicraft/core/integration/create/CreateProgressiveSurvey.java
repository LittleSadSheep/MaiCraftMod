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
 * 分阶段找两端、搜索可能路线、走近逐格核对放置条件，再回起点复查。遇到需要继续探索的端点边界时，把进度交回父任务等待继续选择。
 */
final class CreateProgressiveSurvey {
    enum Status { RUNNING, READY, FAILED }
    private enum Phase {
        SOURCE, DESTINATION, ENDPOINT_PAIRS, ROUTE_SEARCH, CORRIDOR,
        RETURN_SOURCE, READY, FAILED
    }

    record Failure(String code, String detail, FailureType type, List<String> recovery) {}

    private static final int CELLS_PER_TICK = 48;
    private static final int ROUTE_EXPANSIONS_PER_TICK = 64;
    private static final int INITIAL_ROUTE_ENVELOPE_PADDING = 16;

    private final CreateMechanicalPower.Request request;
    private final CreateEndpointEvidenceSearch sourceSearch;
    private final CreateEndpointEvidenceSearch destinationSearch;
    private final Travel travel = new Travel();
    private Phase phase = Phase.SOURCE;
    private List<CreateMechanicalPlan.KineticEndpoint> sourceCandidates = List.of();
    private List<CreateMechanicalPlan.KineticEndpoint> destinationCandidates = List.of();
    private List<BlockPos> receiverCandidates = List.of();
    private int endpointSourceIndex;
    private int endpointTargetIndex;
    private CreateMechanicalPlan.KineticEndpoint source;
    private CreateMechanicalPlan.KineticEndpoint destination;
    private BlockPos receiver;
    private CreateMechanicalPlanner.TentativeRoute route;
    private CreateMechanicalPlanner.ObstacleAwareRouteSearch obstacleRouteSearch;
    private long absorbedRouteSearchRevision;
    private int routeEnvelopePadding = INITIAL_ROUTE_ENVELOPE_PADDING;
    private final Set<Long> rejectedRouteCells = new HashSet<>();
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
    private int rejectedRouteCandidates;
    private boolean sawRouteCandidate;
    private String lastCorridorRejectionDetail;
    private long progressRevision;
    private int authorizedEndpointFrontiers;
    private BlockPos activeEndpointObservation;

    CreateProgressiveSurvey(CreateMechanicalPower.Request request) {
        this.request = request;
        sourceSearch = new CreateEndpointEvidenceSearch(request.source(), true, false);
        destinationSearch = new CreateEndpointEvidenceSearch(
                request.destination(), false, request.allowFreeReceiver());
    }

    Status tick(LocalPlayerContext context) {
        travel.observeGameTime(context.level().getGameTime());
        if (phase == Phase.READY) return Status.READY;
        if (phase == Phase.FAILED) return Status.FAILED;
        return switch (phase) {
            case SOURCE -> surveySource(context);
            case DESTINATION -> surveyDestination(context);
            case ENDPOINT_PAIRS -> beginEndpointPair(context.level());
            case ROUTE_SEARCH -> tickObstacleRouteSearch(context.level());
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
    int rejectedRouteCandidates() { return rejectedRouteCandidates; }
    boolean hasRecentPhysicalProgress(int graceTicks) {
        return travel.hasRecentPhysicalProgress(graceTicks);
    }
    boolean planningInFlight() { return travel.planningInFlight(); }
    long progressRevision() {
        return progressRevision + travel.progressRevision()
                + sourceSearch.progressRevision() + destinationSearch.progressRevision();
    }

    void pause() { travel.pause(); }
    void authorizeNextEndpointFrontier() {
        authorizedEndpointFrontiers++;
        failure = null;
        markProgress();
    }
    void stop() {
        travel.stop();
        obstacleRouteSearch = null;
    }

    private Status surveySource(LocalPlayerContext context) {
        if (context.level().isOutsideBuildHeight(request.source().center())
                || !context.level().getWorldBorder().isWithinBounds(request.source().center())) {
            return fail("source_anchor_outside_world",
                    "the remembered semantic source anchor is outside the current world boundary",
                    FailureType.TARGET_LOST,
                    List.of("remember_source_again", "choose_other_source", "cancel"));
        }
        CreateEndpointEvidenceSearch.Status searchStatus = sourceSearch.tick(context.level());
        CreateEndpointEvidenceSearch.Snapshot survey = sourceSearch.snapshot();
        sourceLoadedCells = survey.observedChunks();
        sourceUnloadedCells = survey.missingChunks();
        if (searchStatus == CreateEndpointEvidenceSearch.Status.NEEDS_OBSERVATION) {
            BlockPos observation = survey.nextObservation() == null
                    ? request.source().center() : survey.nextObservation();
            if (context.level().isLoaded(request.source().center())) {
                if (!observation.equals(activeEndpointObservation)) {
                    if (authorizedEndpointFrontiers <= 0) {
                        return pauseForEndpointEvidence(
                                "source_needs_exploration",
                                "the currently loaded source evidence contains no compatible powered endpoint; further first-person exploration needs an explicit continue decision");
                    }
                    authorizedEndpointFrontiers--;
                    activeEndpointObservation = observation.immutable();
                }
            }
            return travelToward(context, observation, 2,
                    "source_unreachable",
                    "could not reach the next evidence frontier for the semantic source");
        }
        activeEndpointObservation = null;
        if (searchStatus == CreateEndpointEvidenceSearch.Status.EXHAUSTED) {
            return fail("powered_source_not_found",
                    "world-border endpoint evidence was exhausted without a live non-zero-speed vertical-shaft source",
                    FailureType.TARGET_LOST,
                    List.of("restore_source_power", "choose_other_source", "cancel"));
        }
        if (searchStatus != CreateEndpointEvidenceSearch.Status.READY) return Status.RUNNING;
        sourceCandidates = List.copyOf(survey.endpoints());
        markProgress();
        travel.stop();
        transitionTo(Phase.DESTINATION);
        return Status.RUNNING;
    }

    private Status surveyDestination(LocalPlayerContext context) {
        if (context.level().isOutsideBuildHeight(request.destination().center())
                || !context.level().getWorldBorder().isWithinBounds(request.destination().center())) {
            return fail("destination_anchor_outside_world",
                    "the remembered semantic destination anchor is outside the current world boundary",
                    FailureType.TARGET_LOST,
                    List.of("remember_destination_again", "choose_other_endpoint", "cancel"));
        }
        CreateEndpointEvidenceSearch.Status searchStatus = destinationSearch.tick(context.level());
        CreateEndpointEvidenceSearch.Snapshot survey = destinationSearch.snapshot();
        destinationLoadedCells = survey.observedChunks();
        destinationUnloadedCells = survey.missingChunks();
        if (searchStatus == CreateEndpointEvidenceSearch.Status.NEEDS_OBSERVATION) {
            BlockPos observation = survey.nextObservation() == null
                    ? request.destination().center() : survey.nextObservation();
            if (context.level().isLoaded(request.destination().center())) {
                if (!observation.equals(activeEndpointObservation)) {
                    if (authorizedEndpointFrontiers <= 0) {
                        return pauseForEndpointEvidence(
                                "destination_needs_exploration",
                                "the currently loaded destination evidence contains no authoritative compatible endpoint; further first-person exploration needs an explicit continue decision");
                    }
                    authorizedEndpointFrontiers--;
                    activeEndpointObservation = observation.immutable();
                }
            }
            return travelToward(context, observation, 2,
                    "destination_unreachable",
                    "could not reach the next evidence frontier for the semantic destination");
        }
        activeEndpointObservation = null;
        if (searchStatus != CreateEndpointEvidenceSearch.Status.READY
                && searchStatus != CreateEndpointEvidenceSearch.Status.EXHAUSTED) {
            return Status.RUNNING;
        }
        List<CreateMechanicalPlan.KineticEndpoint> unpowered = survey.endpoints().stream()
                .filter(endpoint -> !endpoint.network() || Math.abs(endpoint.speed()) <= 0.0001f)
                .sorted(Comparator.comparingDouble(endpoint ->
                        endpoint.position().distSqr(request.destination().center())))
                .toList();
        if (!unpowered.isEmpty()) {
            destinationCandidates = List.copyOf(unpowered);
            receiverCandidates = List.of();
            markProgress();
            return startCorridorSearch(context.level());
        }
        if (request.allowFreeReceiver()) {
            List<BlockPos> free = survey.freeReceivers();
            if (!free.isEmpty()) {
                destinationCandidates = List.of();
                receiverCandidates = List.copyOf(free);
                markProgress();
                return startCorridorSearch(context.level());
            }
        }
        if (survey.poweredEndpoints() > 0) {
            return fail("destination_already_powered",
                    "the closest fully observed destination evidence has only already-powered endpoints, so requested source membership is not provable",
                    FailureType.TARGET_LOST,
                    List.of("inspect_existing_network", "choose_unpowered_destination", "cancel"));
        }
        return fail("kinetic_destination_not_found",
                "world-border endpoint evidence was exhausted without a compatible unpowered endpoint or approved free receiver",
                FailureType.TARGET_LOST,
                List.of("place_destination_machine", "allow_verified_free_receiver", "cancel"));
    }

    private Status startCorridorSearch(ClientLevel level) {
        endpointSourceIndex = 0;
        endpointTargetIndex = 0;
        rejectedRouteCells.clear();
        sawRouteCandidate = false;
        lastCorridorRejectionDetail = null;
        transitionTo(Phase.ENDPOINT_PAIRS);
        return beginEndpointPair(level);
    }

    private Status beginEndpointPair(ClientLevel level) {
        travel.stop();
        transitionTo(Phase.ENDPOINT_PAIRS);
        if (!endpointPairAvailable()) {
            if (sawRouteCandidate) {
                return fail("progressive_corridor_rejected",
                        exhaustedSurveyDetail(),
                        FailureType.NO_PATH,
                        List.of("move_obstruction", "choose_other_endpoint", "cancel"));
            }
            return fail("mechanical_route_search_exhausted",
                    "the world-bounded 3D chain-drive state graph exhausted every surveyed endpoint pair without a preserving, mechanically continuous route",
                    FailureType.NO_PATH,
                    List.of("move_obstruction", "choose_other_endpoint", "cancel"));
        }
        source = sourceCandidates.get(endpointSourceIndex);
        if (!destinationCandidates.isEmpty()) {
            destination = destinationCandidates.get(endpointTargetIndex);
            receiver = destination.position().relative(destination.shaftFace());
        } else {
            destination = null;
            receiver = receiverCandidates.get(endpointTargetIndex);
        }
        routeEnvelopePadding = INITIAL_ROUTE_ENVELOPE_PADDING;
        markProgress();
        obstacleRouteSearch = CreateMechanicalPlanner.obstacleAwareRouteSearch(
                level, source, destination, receiver, rejectedRouteCells,
                routeEnvelopePadding);
        absorbedRouteSearchRevision = 0L;
        transitionTo(Phase.ROUTE_SEARCH);
        return tickObstacleRouteSearch(level);
    }

    private Status tickObstacleRouteSearch(ClientLevel level) {
        CreateMechanicalPlanner.ObstacleRouteStatus status = obstacleRouteSearch.tick(
                ROUTE_EXPANSIONS_PER_TICK);
        long revision = obstacleRouteSearch.progressRevision();
        if (revision > absorbedRouteSearchRevision) {
            progressRevision += revision - absorbedRouteSearchRevision;
            absorbedRouteSearchRevision = revision;
        }
        if (status == CreateMechanicalPlanner.ObstacleRouteStatus.RUNNING) {
            return Status.RUNNING;
        }
        if (status == CreateMechanicalPlanner.ObstacleRouteStatus.FOUND) {
            sawRouteCandidate = true;
            selectRoute(obstacleRouteSearch.route());
            obstacleRouteSearch = null;
            transitionTo(Phase.CORRIDOR);
            return Status.RUNNING;
        }
        boolean envelopeExpansionMayHelp = obstacleRouteSearch.envelopeExpansionMayHelp();
        obstacleRouteSearch = null;
        if (envelopeExpansionMayHelp) {
            routeEnvelopePadding = routeEnvelopePadding >= Integer.MAX_VALUE / 2
                    ? Integer.MAX_VALUE : routeEnvelopePadding * 2;
            obstacleRouteSearch = CreateMechanicalPlanner.obstacleAwareRouteSearch(
                    level, source, destination, receiver, rejectedRouteCells,
                    routeEnvelopePadding);
            absorbedRouteSearchRevision = 0L;
            markProgress();
            return Status.RUNNING;
        }
        if (advanceEndpointPair()) {
            transitionTo(Phase.ENDPOINT_PAIRS);
            return Status.RUNNING;
        }
        if (sawRouteCandidate) {
            return fail("progressive_corridor_rejected",
                    exhaustedSurveyDetail(),
                    FailureType.NO_PATH,
                    List.of("move_obstruction", "choose_other_endpoint", "cancel"));
        }
        return fail("mechanical_route_search_exhausted",
                "the world-bounded 3D chain-drive state graph exhausted every surveyed endpoint pair without a preserving, mechanically continuous route",
                FailureType.NO_PATH,
                List.of("move_obstruction", "choose_other_endpoint", "cancel"));
    }

    private String exhaustedSurveyDetail() {
        return "all world-bounded 3D mechanically continuous endpoint-pair routes were exhausted after preserving first-person corridor survey rejection"
                + (lastCorridorRejectionDetail == null
                        ? ""
                        : "; last rejection: " + lastCorridorRejectionDetail);
    }

    private boolean endpointPairAvailable() {
        return endpointSourceIndex < sourceCandidates.size()
                && endpointTargetIndex < endpointTargetCount();
    }

    private int endpointTargetCount() {
        return destinationCandidates.isEmpty()
                ? receiverCandidates.size() : destinationCandidates.size();
    }

    private boolean advanceEndpointPair() {
        endpointTargetIndex++;
        if (endpointTargetIndex >= endpointTargetCount()) {
            endpointTargetIndex = 0;
            endpointSourceIndex++;
        }
        markProgress();
        return endpointPairAvailable();
    }

    private void selectRoute(CreateMechanicalPlanner.TentativeRoute selected) {
        route = selected;
        surveyed = new ArrayList<>(java.util.Collections.nCopies(route.positions().size(), null));
        routeSet = Set.copyOf(new HashSet<>(route.positions()));
        corridorIndex = route.positions().size() - 1;
        markProgress();
    }

    // 从目标端向起点复核，每格都要能保留现场、能点击支撑且有站位；一格不成立就排除它并重新找路线。
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
                rejectedRouteCandidates++;
                lastCorridorRejectionDetail = detail;
                if (rejectedRouteCells.add(position.asLong())) markProgress();
                travel.stop();
                obstacleRouteSearch = CreateMechanicalPlanner.obstacleAwareRouteSearch(
                        level, source, destination, receiver, rejectedRouteCells,
                        routeEnvelopePadding);
                absorbedRouteSearchRevision = 0L;
                transitionTo(Phase.ROUTE_SEARCH);
                return Status.RUNNING;
            }
            surveyed.set(corridorIndex, cell);
            corridorIndex--;
            markProgress();
        }
        if (corridorIndex >= 0) return Status.RUNNING;
        travel.stop();
        transitionTo(Phase.RETURN_SOURCE);
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
        boolean inheritsVertical = CreateMechanicalPlacementGeometry.inheritsVerticalAxis(level, position);
        // Reuse the planner's conservative body geometry, then reject any stand occupied by a
        // future route cell. Expanding shells preserve deterministic choice.
        for (int radius = 0; radius <= 4; radius++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                        BlockPos probe = position.offset(dx, dy, dz);
                        if (routeSet.contains(probe) || routeSet.contains(probe.above())) continue;
                        BlockPos stand = CreateMechanicalPlanner.findTravelStand(level, probe, 0, 0);
                        if (stand != null && CreateMechanicalPlacementGeometry.feasibleView(stand, position, inheritsVertical)
                                && (!inheritsVertical || CreateMechanicalPlacementGeometry.clearForJump(level, stand))) return stand;
                    }
                }
            }
        }
        return null;
    }

    // 回到施工起点后，再确认动力源和起点附近已调查的路线没变，才交出可执行计划。
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
        transitionTo(Phase.READY);
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
            markProgress();
            return Status.RUNNING;
        }
        return fail(code, detail + ": " + travel.failure(), FailureType.NO_PATH,
                List.of("make_path_accessible", "choose_other_endpoint", "cancel"));
    }

    private Status fail(String code, String detail, FailureType type, List<String> recovery) {
        travel.stop();
        failure = new Failure(code, detail, type, List.copyOf(recovery));
        transitionTo(Phase.FAILED);
        return Status.FAILED;
    }

    // 保存可恢复的调查阶段，只把本次调用交回父任务；继续授权后从同一进度接着查。
    private Status pauseForEndpointEvidence(String code, String detail) {
        travel.stop();
        failure = new Failure(code, detail, FailureType.TARGET_LOST,
                List.of("continue_endpoint_exploration", "choose_other_endpoint", "cancel"));
        markProgress();
        return Status.FAILED;
    }

    private void markProgress() {
        progressRevision++;
    }

    private void transitionTo(Phase next) {
        if (phase == next) return;
        phase = next;
        markProgress();
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
    // 远处未加载时分段选当前可走到的观察点；走路失败记住该点，避免反复选同一个失败站位。
    static final class Travel {
        enum Status { RUNNING, ARRIVED, FAILED }

        private PlayerNav nav;
        private BlockPos semanticTarget;
        private BlockPos waypoint;
        private final Set<Long> deniedWaypoints = new HashSet<>();
        private String failure = "no loaded waypoint was found";
        private BlockPos lastPlayerPosition;
        private long observedGameTime = Long.MIN_VALUE;
        private long lastPhysicalProgressGameTime = Long.MIN_VALUE;
        private long progressRevision;

        Status tick(LocalPlayer player, BlockPos target, int radius) {
            long gameTime = player.level().getGameTime();
            observedGameTime = gameTime;
            recordPlayerMovement(player.blockPosition(), gameTime);
            if (semanticTarget == null || !semanticTarget.equals(target)) {
                stop();
                semanticTarget = target.immutable();
                lastPlayerPosition = player.blockPosition().immutable();
            }
            if (horizontalDistanceSq(player.blockPosition(), target) <= radius * radius
                    && player.level().isLoaded(target)) {
                markPhysicalProgress(gameTime);
                stop();
                return Status.ARRIVED;
            }
            if (nav == null) {
                waypoint = chooseWaypoint(player, target);
                if (waypoint == null) {
                    failure = "the current loaded-frontier waypoint generator exhausted its distinct standable candidates toward the semantic target"
                            + (deniedWaypoints.isEmpty()
                                    ? ""
                                    : "; rejected_waypoints=" + deniedWaypoints.size()
                                            + ", last_navigation_failure=" + failure);
                    return Status.FAILED;
                }
                BlockPos frozen = waypoint;
                nav = new PlayerNav(player, frozen, 0.9,
                        () -> player.blockPosition().distSqr(frozen) <= 1.0);
            }
            PlayerNav.Status status = nav.tick();
            if (status == PlayerNav.Status.RUNNING) return Status.RUNNING;
            if (status == PlayerNav.Status.ARRIVED) {
                markPhysicalProgress(gameTime);
                nav.stop();
                nav = null;
                return Status.RUNNING;
            }
            failure = nav.failReason();
            if (waypoint != null && deniedWaypoints.add(waypoint.asLong())) {
                // Rejecting one concrete waypoint advances the finite candidate search. It is
                // semantic progress, but deliberately not reported as physical movement.
                progressRevision++;
            }
            nav.stop();
            nav = null;
            return Status.RUNNING;
        }

        String failure() { return failure; }

        boolean hasRecentPhysicalProgress(int graceTicks) {
            if (nav != null && nav.hasRecentPhysicalProgress(graceTicks)) return true;
            if (observedGameTime == Long.MIN_VALUE
                    || lastPhysicalProgressGameTime == Long.MIN_VALUE) return false;
            long age = observedGameTime - lastPhysicalProgressGameTime;
            return age >= 0 && age <= Math.max(0, graceTicks);
        }

        boolean planningInFlight() { return nav != null && nav.planningInFlight(); }

        long progressRevision() { return progressRevision; }

        void observeGameTime(long gameTime) {
            observedGameTime = gameTime;
        }

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
            lastPlayerPosition = null;
        }

        private BlockPos chooseWaypoint(LocalPlayer player, BlockPos target) {
            ClientLevel level = player.clientLevel;
            BlockPos from = player.blockPosition();
            double dx = target.getX() - from.getX();
            double dz = target.getZ() - from.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length < 1.0) {
                BlockPos stand = CreateMechanicalPlanner.findTravelStand(level, target, 6, 16);
                return stand != null && !deniedWaypoints.contains(stand.asLong())
                        ? stand : null;
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

        private void recordPlayerMovement(BlockPos current, long gameTime) {
            BlockPos frozen = current.immutable();
            if (lastPlayerPosition != null && !lastPlayerPosition.equals(frozen)) {
                markPhysicalProgress(gameTime);
            }
            lastPlayerPosition = frozen;
        }

        private void markPhysicalProgress(long gameTime) {
            progressRevision++;
            lastPhysicalProgressGameTime = gameTime;
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
