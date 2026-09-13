// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.utility;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.integration.create.CreateMechanicalPower;
import org.maiwithu.maicraft.core.integration.machine.assembly.MekanismConfigureTaskRecord;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.core.integration.machine.utility.UtilityConnectionEvidence.*;

/** Establishes utility availability; commissioning the machine's recipe remains a separate task. */
final class UtilityConnectionTask extends AbstractCompanionTask<UtilityConnectionTaskRecord> {
    private enum Phase { SOURCE, TARGET, PREPARE, BUILD, TOOL, CONFIGURE, CONNECTIONS, EXISTING_CONNECTION, SOURCE_AFTER, TARGET_AFTER, DONE }
    private final Level world;
    private final UtilityConnectionReads reads = new UtilityConnectionReads();
    private final SemanticMaterialSupplyCoordinator supply = new SemanticMaterialSupplyCoordinator();
    private final List<BlockPos> endpoints;
    private final List<JsonObject> connections = new ArrayList<>();
    private Phase phase = Phase.SOURCE;
    private JsonObject source, target, sourceAfter, targetAfter;
    private Block sourceBlock, targetBlock;
    private BlockEntity sourceEntity, targetEntity;
    private BlockEntity ownedSourceCable;
    private Direction sourceFace;
    private UtilityConnectionPlanner.Route route;
    private Task child;
    private TaskRecord childRecord;
    private Map<String, Object> lastChild = Map.of();
    private int edgeStart;
    private boolean routeBuilt, nativeConnected, sourcePowered, destinationPowered, noChange;
    private String failureCode;

    UtilityConnectionTask(LocalPlayer player, UtilityConnectionTaskRecord record) {
        super(player, record); world = player.level();
        endpoints = List.of(record.request.sourceAnchor(), record.request.target());
    }
    @Override protected void onStart() {
        if (!List.of("kinetic", "energy").contains(r.request.medium())) {
            failure("utility_medium_connection_unsupported"); return;
        }
        if (r.request.medium().equals("kinetic") && r.request.targetFace().getAxis() != Direction.Axis.Y) {
            failure("utility_kinetic_vertical_interface_required"); return;
        }
        if (r.request.medium().equals("energy") && !r.request.resource().isEmpty()
                && !r.request.resource().equals("neoforge:energy")) { failure("utility_energy_standard_unsupported"); return; }
        if (r.request.medium().equals("energy") && !UtilityCableConstruction.available()) { failure("utility_mekanism_cable_unavailable"); return; }
        if (!endpoints.stream().allMatch(world::isLoaded)) { failure("utility_endpoints_must_be_loaded"); return; }
        if (!BuiltInRegistries.BLOCK.getKey(world.getBlockState(r.request.target()).getBlock()).toString().equals(r.request.targetBlockId())) {
            failure("utility_declared_input_changed"); return;
        }
        sourceBlock = world.getBlockState(r.request.sourceAnchor()).getBlock();
        targetBlock = world.getBlockState(r.request.target()).getBlock();
        sourceEntity = world.getBlockEntity(r.request.sourceAnchor()); targetEntity = world.getBlockEntity(r.request.target());
    }
    @Override protected TaskState onTick() {
        try {
            if (!current()) return failure("utility_endpoints_changed_or_protected");
            if (!UtilityConnectionReads.requireServer("machine.snapshot", "utility_server_snapshot_required",
                    () -> stop(player, StopReason.PREEMPTED))) return TaskState.RUNNING;
            if ((r.request.medium().equals("energy") || phase == Phase.EXISTING_CONNECTION)
                    && !UtilityConnectionReads.requireServer("machine.connections", "utility_server_connections_required",
                    () -> stop(player, StopReason.PREEMPTED))) return TaskState.RUNNING;
            if (supply.active()) {
                var tick = NavigationSafetyContext.withPreservedStructures(route.path(), () -> supply.tick(player, this::runChild));
                r.extendDeadlineTo(supply.childDeadline());
                if (tick.status() == SemanticMaterialSupplyCoordinator.Status.FAILED) return failure("utility_configurator_supply_failed: " + tick.message());
                return TaskState.RUNNING;
            }
            if (child != null) return tickChild();
            return switch (phase) {
                case SOURCE -> readSource(false);
                case TARGET -> readTarget(false);
                case PREPARE -> prepare();
                case TOOL -> tool();
                case CONNECTIONS -> inspectConnections();
                case EXISTING_CONNECTION -> inspectExistingKinetic();
                case SOURCE_AFTER -> readSource(true);
                case TARGET_AFTER -> readTarget(true);
                case DONE -> TaskState.SUCCESS;
                default -> failure("utility_missing_child_receipt");
            };
        } catch (IllegalArgumentException | IllegalStateException failure) { return failure(failure.getMessage()); }
    }
    private boolean current() {
        return player.level() == world && r.dimension.equals(world.dimension().location().toString()) && player.mayBuild()
                && endpoints.stream().allMatch(at -> world.isLoaded(at) && !NavigationSafetyContext.protectsUse(at))
                && world.getBlockState(r.request.sourceAnchor()).getBlock() == sourceBlock
                && world.getBlockState(r.request.target()).getBlock() == targetBlock
                && world.getBlockEntity(r.request.sourceAnchor()) == sourceEntity && world.getBlockEntity(r.request.target()) == targetEntity
                && (ownedSourceCable == null || world.isLoaded(route.cables().getFirst())
                    && world.getBlockEntity(route.cables().getFirst()) == ownedSourceCable);
    }
    private TaskState readSource(boolean after) {
        JsonObject observed = snapshot(r.request.sourceAnchor(), after ? List.of(sourceFace) : List.of(Direction.values()));
        if (observed == null) return TaskState.RUNNING;
        if (!text(observed, "block_id").equals(BuiltInRegistries.BLOCK.getKey(sourceBlock).toString()))
            return failure("utility_native_source_changed");
        if (after) { sourceAfter = observed; phase = Phase.TARGET_AFTER; }
        else { source = observed; phase = Phase.TARGET; }
        return TaskState.RUNNING;
    }
    private TaskState readTarget(boolean after) {
        JsonObject observed = snapshot(r.request.target(), List.of(r.request.targetFace()));
        if (observed == null) return TaskState.RUNNING;
        if (!text(observed, "block_id").equals(r.request.targetBlockId())) return failure("utility_native_input_changed");
        if (!after) { target = observed; phase = Phase.PREPARE; return TaskState.RUNNING; }
        targetAfter = observed;
        if (r.request.medium().equals("kinetic")) {
            nativeConnected = sameKineticNetwork(sourceAfter, targetAfter);
            sourcePowered = kineticPowered(sourceAfter, r.request.minRpm());
            destinationPowered = kineticPowered(targetAfter, r.request.minRpm());
            if (!nativeConnected || !sourcePowered || !destinationPowered) return failure("utility_kinetic_network_or_speed_not_ready");
        } else {
            if (!energyFaces(sourceAfter, true).contains(sourceFace)
                    || !energyFaces(targetAfter, false).contains(r.request.targetFace()))
                return failure("utility_energy_endpoint_direction_changed");
            sourcePowered = storedEnergy(sourceAfter, sourceFace) > 0;
            destinationPowered = storedEnergy(targetAfter, r.request.targetFace()) > 0;
        }
        phase = Phase.DONE; return TaskState.SUCCESS;
    }
    private JsonObject snapshot(BlockPos position, List<Direction> faces) {
        if (!near(List.of(position))) return null;
        JsonObject reply = reads.call("machine.snapshot", snapshotQuery(position, faces));
        return reply == null ? null : observation(reply, position, r.dimension);
    }
    private TaskState prepare() {
        if (r.request.medium().equals("kinetic")) {
            if (!shaft(target, r.request.targetFace())) return failure("utility_declared_face_has_no_native_shaft");
            if (!kineticPowered(source, r.request.minRpm())) return failure("utility_source_unpowered_or_slow");
            if (kineticPowered(target, 0)) {
                if (!existingKineticPower(source, target, r.request.targetFace(), r.request.minRpm()))
                    return failure("utility_input_already_powered_inspect_existing_network");
                sourceFace = List.of(Direction.values()).stream().filter(face -> shaft(source, face)).findFirst().orElse(Direction.UP);
                phase = Phase.EXISTING_CONNECTION; return TaskState.RUNNING;
            }
            sourceFace = List.of(Direction.UP, Direction.DOWN).stream().filter(face -> shaft(source, face)
                    && world.isLoaded(r.request.sourceAnchor().relative(face))
                    && world.getBlockState(r.request.sourceAnchor().relative(face)).isAir()).findFirst().orElse(null);
            if (sourceFace == null) return failure("utility_source_needs_exposed_vertical_shaft");
            var request = CreateMechanicalPower.Request.preserving(
                    new CreateMechanicalPower.Endpoint(r.sourceLabel, r.request.sourceAnchor(), sourceFace),
                    new CreateMechanicalPower.Endpoint(r.inputId, r.request.target(), r.request.targetFace()));
            phase = Phase.BUILD;
            start(CreateMechanicalPower.task(r.getToolCallId() + "-shaft", r.getDeadlineGameTime(), request,
                    r.materialPolicy, List.of(), false, r.protectedLabels));
        } else {
            if (!energyFaces(target, false).contains(r.request.targetFace())) return failure("utility_target_face_not_native_energy_input");
            var faces = energyFaces(source, true).stream().filter(face -> storedEnergy(source, face) > 0).toList();
            if (faces.isEmpty()) return failure("utility_source_has_no_powered_native_energy_outlet");
            route = UtilityConnectionPlanner.plan(r.request.sourceAnchor(), faces, r.request.target(), r.request.targetFace(),
                    at -> UtilityCableConstruction.empty(player, at, endpoints));
            sourceFace = route.sourceFace(); phase = Phase.BUILD;
            start(UtilityCableConstruction.task(r.getToolCallId(), r.getDeadlineGameTime(), r.materialPolicy, r.protectedLabels, route, this::current));
        }
        return TaskState.RUNNING;
    }
    private TaskState inspectExistingKinetic() {
        List<BlockPos> path = List.of(r.request.target().relative(r.request.targetFace()), r.request.target());
        if (!world.isLoaded(path.getFirst()) || world.getBlockState(path.getFirst()).isAir())
            return failure("utility_input_already_powered_but_declared_face_unconnected");
        if (!near(path)) return TaskState.RUNNING;
        JsonObject query = new JsonObject(); JsonArray positions = new JsonArray(); path.forEach(at -> positions.add(position(at)));
        query.addProperty("system", "create"); query.addProperty("medium", "kinetic"); query.add("path", positions);
        query.addProperty("to_face", r.request.targetFace().getSerializedName());
        JsonObject result = reads.call("machine.connections", query); if (result == null) return TaskState.RUNNING;
        connections.add(result);
        if (!connected(result, r.dimension, positions))
            return failure("utility_existing_kinetic_face_not_native_connected");
        noChange = true; nativeConnected = true; phase = Phase.SOURCE_AFTER; return TaskState.RUNNING;
    }
    private TaskState tool() {
        if (!UtilityCableConstruction.matches(player, route.cables())) return failure("utility_cable_route_changed");
        // Transmitter-to-transmitter networks join natively; only a new cable beside a machine needs pull mode.
        if (BuiltInRegistries.BLOCK.getKey(sourceBlock).getPath().endsWith("universal_cable")) { phase = Phase.CONNECTIONS; return TaskState.RUNNING; }
        ResourceLocation configurator = ResourceLocation.parse("mekanism:configurator");
        boolean carried = player.getInventory().items.stream().anyMatch(stack -> !stack.isEmpty()
                && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(configurator));
        if (!carried) {
            supply.begin(player, r.getToolCallId(), r.getDeadlineGameTime(), new SemanticMaterialSupplyCoordinator.Demand(
                    List.of(configurator), 1, "configure new utility cable source face"), r.materialPolicy, List.of(), false, r.protectedLabels);
            return TaskState.RUNNING;
        }
        phase = Phase.CONFIGURE;
        start(new MekanismConfigureTaskRecord(r.getToolCallId() + "-pull", r.getDeadlineGameTime(), route.cables().getFirst(),
                sourceFace.getOpposite(), "energy", "pull"));
        return TaskState.RUNNING;
    }
    private TaskState inspectConnections() {
        if (!UtilityCableConstruction.matches(player, route.cables())) return failure("utility_cable_route_changed");
        if (edgeStart >= route.path().size() - 1) { nativeConnected = true; phase = Phase.SOURCE_AFTER; return TaskState.RUNNING; }
        List<BlockPos> segment = route.path().subList(edgeStart, Math.min(route.path().size(), edgeStart + 8));
        if (!near(segment)) return TaskState.RUNNING;
        JsonObject query = new JsonObject(); JsonArray path = new JsonArray(); segment.forEach(at -> path.add(position(at)));
        query.addProperty("system", "mekanism"); query.addProperty("medium", "energy"); query.add("path", path);
        JsonObject result = reads.call("machine.connections", query);
        if (result == null) return TaskState.RUNNING;
        connections.add(result);
        if (!connected(result, r.dimension, path))
            return failure("utility_native_energy_connection_unverified");
        edgeStart += segment.size() - 1; return TaskState.RUNNING;
    }
    private boolean near(List<BlockPos> positions) {
        if (reads.pending()) return true;
        java.util.function.BooleanSupplier ready = () -> positions.stream().allMatch(at -> world.isLoaded(at)
                && player.distanceToSqr(at.getCenter()) <= 7.5 * 7.5);
        if (ready.getAsBoolean()) { stopNav(); return true; }
        BlockPos middle = positions.get(positions.size() / 2);
        if (nav == null) nav = PlayerNav.toGoal(player, () -> NavGoal.near(middle, 2), 1.0, ready, PlayerNav.ContextProvider.DEFAULT);
        var state = NavigationSafetyContext.withPreservedStructures(endpoints, nav::tick);
        if (state == PlayerNav.Status.FAILED || state == PlayerNav.Status.ARRIVED && !ready.getAsBoolean())
            throw new IllegalArgumentException("utility_native_observation_unreachable");
        return false;
    }
    private void start(TaskRecord record) { stopNav(); childRecord = record; child = TaskFactory.create(player, record); }
    private TaskState tickChild() {
        TaskState state = world.getGameTime() >= childRecord.getDeadlineGameTime() ? TaskState.TIMEOUT
                : NavigationSafetyContext.withPreservedStructures(endpoints, () -> runChild(child));
        r.extendDeadlineTo(childRecord.getDeadlineGameTime());
        if (state == null) return TaskState.RUNNING;
        if (state == TaskState.TIMEOUT) child.stop(player, StopReason.REPLACED);
        var result = child.result(state); lastChild = result.data(); child = null; childRecord = null;
        if (!result.success()) return failure("utility_native_stage_failed: " + result.message());
        if (phase == Phase.BUILD) {
            routeBuilt = true;
            if (r.request.medium().equals("energy")) {
                ownedSourceCable = world.getBlockEntity(route.cables().getFirst());
                if (ownedSourceCable == null) return failure("utility_built_cable_entity_unavailable");
            }
            phase = r.request.medium().equals("energy") ? Phase.TOOL : Phase.SOURCE_AFTER;
        }
        else phase = Phase.CONNECTIONS;
        return TaskState.RUNNING;
    }
    private TaskState failure(String code) { failureCode = code; fail(code, FailureType.UNKNOWN); return TaskState.FAILED; }
    @Override public void stop(LocalPlayer companion, StopReason reason) {
        if (child != null) child.stop(companion, reason);
        super.stop(companion, reason);
    }
    @Override protected void cleanup() {
        if (child != null) { child.stop(player, StopReason.REPLACED); lastChild = child.result(TaskState.CANCELLED).data(); child = null; }
        supply.cancel(player); reads.cancel(); super.cleanup();
    }
    @Override public Map<String, Object> progress() { return Map.of("task", name(), "phase", phase.name().toLowerCase(), "route_built", routeBuilt); }
    @Override protected Map<String, Object> resultData() {
        Map<String, Object> result = readiness(routeBuilt, nativeConnected, sourcePowered, destinationPowered, noChange);
        result.put("input_id", r.inputId); result.put("source_label", r.sourceLabel); result.put("medium", r.request.medium());
        result.put("endpoint_blocks_preserved", true); result.put("target", position(r.request.target()));
        result.put("target_face", r.request.targetFace().getSerializedName()); result.put("last_native_stage", lastChild);
        result.put("connection_evidence", List.copyOf(connections));
        if (sourceAfter != null) result.put("source_observation", sourceAfter);
        if (targetAfter != null) result.put("target_observation", targetAfter);
        if (failureCode != null) result.put("failure_code", failureCode);
        return result;
    }
    @Override protected String successMessage() { return completionMessage(noChange, sourcePowered, destinationPowered); }
}
