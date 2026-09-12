// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.client.server.ServerAssistClient;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskState;

/** Coordinates native construction, bounded input budgets and production evidence without duplicating their executors. */
final class MachineProductionTask extends AbstractCompanionTask<MachineProductionTaskRecord> implements ProductionWork {
    private enum Phase { CHECK, BUILD, CONFIGURE, PREPARE, BASELINE, SUPPLY, ADMIT_START, START, REFRESH, OBSERVE, FINAL_VERIFY, DONE }
    private final ProductionRequestSlot requests = new ProductionRequestSlot();
    private ProductionInputSupply supply;
    private final ProductionPreparation preparation;
    private final ProductionConfigurationSupply configurationSupply;
    private final ProductionProgressWatchdog watchdog;
    private ProductionOutputMonitor output;
    private boolean started;
    private Phase phase = Phase.CHECK;
    private Task construction;
    private int configuration;
    private boolean configurationRead;
    private BlockPos navigationTarget;
    private BlockPos interactionStance;
    private final java.util.Set<Long> rejectedStances = new java.util.HashSet<>();
    private String failureCode;
    private Map<String, Object> constructionResult = Map.of();

    MachineProductionTask(LocalPlayer player, MachineProductionTaskRecord record) {
        super(player, record);
        preparation = new ProductionPreparation(player, record.plan, this);
        configurationSupply = new ProductionConfigurationSupply(player, record, this);
        var observation = record.plan.manifest().observation();
        watchdog = new ProductionProgressWatchdog(observation.windowTicks(), observation.maxIdleTicks(),
                record.plan.manifest().links().stream().mapToInt(link -> link.path().size()).max().orElse(0));
    }

    @Override protected TaskState onTick() {
        if (!player.level().dimension().location().toString().equals(r.plan.dimension()))
            return failure("production_world_changed", "Production cannot continue in a different dimension");
        try {
            // The construction child owns its target permissions; planned machine cells are not yet protected assets.
            if (phase == Phase.CHECK || phase == Phase.BUILD) return advance();
            return org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext.withProtectedArea(
                    r.plan.positions(), java.util.List.of(), this::advance);
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            return failure("production_stage_failed", unavailable.getMessage());
        }
    }

    private TaskState advance() {
        switch (phase) {
            case CHECK -> {
                for (String capability : java.util.List.of("machine.snapshot", "machine.recipe", "machine.connections",
                        "machine.production_events", "inventory.quote", "inventory.transfer"))
                    if (!ServerAssistClient.supported(capability) && !ServerAssistClient.renegotiating(capability))
                        return failure("production_server_support_required", "Verified production requires " + capability
                                + "; ordinary client-only construction remains available without a production requirement");
                if (!r.plan.manifest().configurations().isEmpty() && !ServerAssistClient.supported("machine.configuration")
                        && !ServerAssistClient.renegotiating("machine.configuration"))
                    return failure("production_configuration_reader_required", "The server cannot revalidate native configuration values");
                phase = r.construction == null ? Phase.CONFIGURE : Phase.BUILD;
            }
            case BUILD -> {
                if (construction == null) construction = TaskFactory.create(player, r.construction);
                TaskState state = runChild(construction);
                r.extendDeadlineTo(r.construction.getDeadlineGameTime());
                if (state == null || !state.isTerminal()) return TaskState.RUNNING;
                var result = construction.result(state); constructionResult = result.data(); construction = null;
                if (!result.success()) return failure("production_construction_failed", result.message());
                phase = Phase.CONFIGURE;
            }
            case CONFIGURE -> { if (configure("configure")) { configuration = 0; phase = Phase.PREPARE; } }
            case PREPARE -> {
                if (preparation.tick()) {
                    if (!preparation.compilation().canEnter("supply"))
                        return failure("production_design_blocked", preparation.compilation().report().toString());
                    r.plan.bindResolved(preparation.compilation().report());
                    supply = new ProductionInputSupply(player, r, r.plan, this, r.protectedLabels);
                    output = new ProductionOutputMonitor(r.plan, this);
                    phase = Phase.BASELINE;
                }
            }
            case BASELINE -> { if (output.baseline()) phase = Phase.SUPPLY; }
            case SUPPLY -> {
                if (supply.tick()) {
                    if (started) phase = Phase.OBSERVE;
                    else { preparation.refresh(); phase = Phase.ADMIT_START; }
                }
            }
            case ADMIT_START -> {
                if (preparation.tick()) {
                    if (!preparation.compilation().canEnter("start"))
                        return failure("production_start_not_ready", preparation.compilation().report().toString());
                    phase = Phase.START;
                }
            }
            case START -> {
                if (configure("start")) {
                    started = true; watchdog.noteInput(player.level().getGameTime()); preparation.refresh(); phase = Phase.REFRESH;
                }
            }
            case REFRESH -> {
                if (preparation.tick()) {
                    if (!preparation.compilation().valid())
                        return failure("production_running_design_changed", preparation.compilation().report().toString());
                    // Reading completed native events remains useful after a finite batch becomes idle.
                    phase = Phase.OBSERVE;
                }
            }
            case OBSERVE -> {
                boolean finished = output.tick();
                boolean observedRound = output.consumeRoundBoundary();
                if (finished) {
                    preparation.refresh(); phase = Phase.FINAL_VERIFY; return TaskState.RUNNING;
                }
                if (observedRound && !requests.pending() && supply.needsRefill()) {
                    supply.refill(); phase = Phase.SUPPLY;
                    // Start actions have already run; do not toggle or submit them again after refilling.
                    configuration = r.plan.manifest().configurations().size();
                } else if (observedRound && !requests.pending()) {
                    String stalled = watchdog.failure(output.processingCoverageTick(), output.processingIdleTicks(),
                            output.latestProcessingTick(), output.productionWindowVerified(), output.lastAttributedDeliveryTick(),
                            supply.exhausted(), supply.pacingFailure());
                    if (stalled != null) return failure("production_observation_stalled", stalled);
                }
            }
            case FINAL_VERIFY -> {
                if (preparation.tick()) {
                    if (!preparation.compilation().valid() || !preparation.topologyVerified())
                        return failure("production_connection_unverified", "The declared native machine connections changed or remain unverified");
                    phase = Phase.DONE; r.verified(); return TaskState.SUCCESS;
                }
            }
            case DONE -> { return TaskState.SUCCESS; }
        }
        return TaskState.RUNNING;
    }

    private boolean configure(String stage) {
        var configurations = r.plan.manifest().configurations();
        while (configuration < configurations.size()) {
            var next = configurations.get(configuration);
            if (!next.stage().equals(stage)) { configuration++; continue; }
            if (!configurationRead) {
                if (!observe(r.plan.at(r.plan.node(next.node())))) return false;
                JsonObject current = request("machine.configuration", r.plan.configurationBody(next), false);
                if (current == null) return false;
                preparation.observedConfiguration(next.id(), current);
                if (current.has("status") && current.get("status").getAsString().equals("matched")) {
                    configuration++; return false;
                }
                configurationRead = true;
                return false;
            }
            if (!requests.pending() && !configurationSupply.prepare(next)) return false;
            if (!approach(r.plan.at(r.plan.node(next.node())))) return false;
            if (next.arguments().get("action").getAsString().startsWith("create.")) {
                org.maiwithu.maicraft.entity.InputDriver.sneak(player, true);
                if (!player.isSecondaryUseActive()) return false;
            }
            JsonObject response = request(next.operation(), r.plan.configurationBody(next), true);
            if (response == null) return false;
            String status = response.has("status") ? response.get("status").getAsString() : "unknown";
            if (!status.equals("applied") && !status.equals("no_change"))
                throw new IllegalStateException("production_configuration_unconfirmed: " + next.id() + ": " + response);
            preparation.configured(next.id(), response); configuration++; configurationRead = false;
            org.maiwithu.maicraft.entity.InputDriver.sneak(player, false);
            r.extendDeadlineTo(player.level().getGameTime() + 1_200);
            return false;
        }
        return true;
    }

    @Override public boolean approach(BlockPos position) {
        if (requests.pending()) return true;
        if (navigationTarget != null && !navigationTarget.equals(position)) {
            stopNav(); interactionStance = null; rejectedStances.clear();
        }
        if (!player.level().isLoaded(position) || player.distanceToSqr(position.getCenter()) > 144) {
            observe(position); return false;
        }
        if (player.distanceToSqr(position.getCenter()) <= 9
                && ProductionInteractionSight.visible(player.level(), player, position)) { stopNav(); return true; }
        if (nav == null) {
            navigationTarget = position.immutable();
            if (interactionStance == null) interactionStance =
                    org.maiwithu.maicraft.core.integration.machine.assembly.AssemblyInteractionGeometry.nearestStand(
                            player, position, rejectedStances, eyes -> {
                                var feet = eyes.subtract(0, player.getEyeHeight(net.minecraft.world.entity.Pose.STANDING), 0);
                                return feet.distanceToSqr(position.getCenter()) <= 9
                                        ? ProductionInteractionSight.aimFrom(player.level(), player, position, eyes) : null;
                            }, net.minecraft.world.entity.Pose.STANDING);
            if (interactionStance == null) throw new IllegalArgumentException("production_access_unavailable: no visible reachable interaction stance");
            nav = PlayerNav.to(player,
                    () -> org.maiwithu.maicraft.core.pathing.goal.GoalCompiler.standOn(interactionStance), 1.0,
                    () -> net.minecraft.world.phys.Vec3.atBottomCenterOf(interactionStance).distanceToSqr(player.position()) < .16
                            && ProductionInteractionSight.visible(player.level(), player, position), PlayerNav.ContextProvider.DEFAULT);
        }
        var status = nav.tick();
        if (status == PlayerNav.Status.FAILED || status == PlayerNav.Status.ARRIVED) {
            if (status == PlayerNav.Status.ARRIVED && ProductionInteractionSight.visible(player.level(), player, position)) {
                stopNav(); return true;
            }
            rejectedStances.add(interactionStance.asLong()); interactionStance = null; stopNav();
        }
        return false;
    }

    @Override public boolean observe(BlockPos position) {
        if (requests.pending()) return true;
        if (navigationTarget != null && !navigationTarget.equals(position)) stopNav();
        if (player.level().isLoaded(position) && player.distanceToSqr(position.getCenter()) <= 144) {
            stopNav(); return true;
        }
        if (nav == null) {
            navigationTarget = position.immutable();
            nav = PlayerNav.toGoal(player, () -> NavGoal.near(position, 10), 1.0,
                    () -> player.level().isLoaded(position) && player.distanceToSqr(position.getCenter()) <= 144,
                    PlayerNav.ContextProvider.DEFAULT);
        }
        if (nav.tick() == PlayerNav.Status.FAILED) {
            stopNav(); throw new IllegalArgumentException("production_observation_unreachable");
        }
        return false;
    }

    @Override public JsonObject request(String operation, JsonObject arguments, boolean mutating) { return requests.call(operation, arguments, mutating); }
    @Override public TaskState advanceChild(Task child) { return runChild(child); }
    @Override public void stopMovement() { stopNav(); }
    @Override public Map<String, Long> processingProgress() { return output == null ? Map.of() : output.processingProgress(); }
    @Override public void extendDeadlineTo(long tick) { r.extendDeadlineTo(tick); }
    @Override public void initialSupply(String source,
            org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Resource resource,
            long credited, JsonObject snapshot) { preparation.initialSupply(source, resource, credited, snapshot); }
    @Override public void confirmedSupply(String source,
            org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Resource resource,
            String requestId, JsonObject result) {
        preparation.supplied(source, resource, requestId, result);
        if (result.get("transferred").getAsLong() > 0) watchdog.noteInput(result.get("tick").getAsLong());
    }
    private TaskState failure(String code, String message) { failureCode = code; fail(message, FailureType.UNKNOWN); return TaskState.FAILED; }

    @Override protected void cleanup() {
        try {
            Task previous = construction; construction = null;
            if (previous != null) {
                try { previous.stop(player, StopReason.REPLACED); }
                finally { previous.result(TaskState.CANCELLED); }
            }
        } finally {
            try { try { requests.cancel(); } finally { releaseObservation(); } }
            finally {
                try { if (supply != null) supply.cancel(); }
                finally { try { configurationSupply.cancel(); } finally { super.cleanup(); } }
            }
        }
    }
    private void releaseObservation() {
        if (output == null) return;
        for (JsonObject body : output.releaseBodies()) {
            try { ServerAssistClient.submit("machine.production_events", body, false, null); }
            catch (RuntimeException unavailable) {
                org.maiwithu.maicraft.core.Constants.LOG.debug("Production watch release deferred to connection cleanup", unavailable);
            }
        }
    }
    @Override protected String successMessage() { return "Machine production and delivery verified over the declared observation window"; }
    @Override protected Map<String, Object> resultData() {
        var result = new LinkedHashMap<String, Object>();
        result.put("phase", phase.name().toLowerCase(java.util.Locale.ROOT));
        result.put("machine_production_verified", phase == Phase.DONE);
        result.put("production_preparation", preparation.report());
        result.put("production_observation", output == null ? Map.of("status", "not_started") : output.report());
        result.put("production_supply", supply == null ? Map.of("status", "not_started") : supply.report());
        result.put("last_server_operation", requests.report());
        if (!constructionResult.isEmpty()) result.put("construction", constructionResult);
        if (failureCode != null) result.put("failure_code", failureCode);
        return result;
    }
}
