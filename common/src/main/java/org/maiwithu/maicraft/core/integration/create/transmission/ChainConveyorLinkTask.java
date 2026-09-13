// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.server.ServerAssistClient;
import org.maiwithu.maicraft.client.server.ServerSessionRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;

/** Uses two real held-chain block interactions; the second native action is never replayed. */
final class ChainConveyorLinkTask extends AbstractCompanionTask<ChainConveyorLinkTaskRecord> {
    private enum Phase { MATERIALS, EQUIP, FIRST_READ, FIRST_CLICK, FIRST_CONFIRM, SECOND_READ, SECOND_CLICK, SECOND_CONFIRM, VERIFY_SECOND, VERIFY_FIRST, RESTORE }
    private final Level world;
    private final ChainConveyorInventory inventory;
    private final ChainConveyorRead reads = new ChainConveyorRead();
    private final SemanticMaterialSupplyCoordinator supply = new SemanticMaterialSupplyCoordinator();
    private Phase phase = Phase.MATERIALS;
    private BlockEntity firstEntity, secondEntity;
    private Set<BlockPos> firstBefore = Set.of(), secondBefore = Set.of();
    private NativeActionReceipt action;
    private BlockPos navigating;
    private JsonObject firstProof, secondProof, firstAdmission;
    private int cost, countBefore, countAfter, approachTicks;
    private boolean selectedByTask, secondSubmitted, effectSettled, verified, noChange, restored, ownedSelectionPending, geometryChecked;
    private String failure;
    private boolean serverAssisted, creativeExempt;
    private ChainConveyorBridge.Kinetic firstKinetic, secondKinetic;
    private Task creativeSupply;
    private BlockPos serverObservedEndpoint;
    private boolean bothServerVerified, firstSelectionConfirmed;
    ChainConveyorLinkTask(LocalPlayer player, ChainConveyorLinkTaskRecord record) {
        super(player, record); world = player.level(); inventory = new ChainConveyorInventory(player);
    }
    @Override protected void onStart() {
        try {
            if (!ChainConveyorBridge.clientLinkAvailable()) throw new IllegalArgumentException("chain_conveyor_native_api_unavailable");
            serverAssisted = ServerAssistClient.serverSupported("machine.snapshot"); creativeExempt = player.getAbilities().instabuild;
            if (!serverAssisted && !ServerAssistClient.nativeFallbackAllowed("machine.snapshot")) throw new IllegalArgumentException("chain_conveyor_server_policy_denied");
            if (!world.isLoaded(r.first) || !world.isLoaded(r.second)) throw new IllegalArgumentException("chain_conveyor_endpoints_unloaded");
            firstEntity = world.getBlockEntity(r.first); secondEntity = world.getBlockEntity(r.second);
            firstBefore = ChainConveyorBridge.connections(firstEntity); secondBefore = ChainConveyorBridge.connections(secondEntity);
            cost = ChainConveyorBridge.linkCost(r.second.subtract(r.first));
            if (ChainConveyorBridge.existingLink(firstBefore, secondBefore, r.second.subtract(r.first))) {
                noChange = true;
                phase = player.distanceToSqr(r.first.getCenter()) < player.distanceToSqr(r.second.getCenter()) ? Phase.VERIFY_FIRST : Phase.VERIFY_SECOND;
                return;
            }
            if (ChainConveyorBridge.selection().first() != null) throw new IllegalArgumentException("chain_conveyor_existing_selection_preserved");
            validatePair();
        } catch (IllegalArgumentException problem) { stop(problem.getMessage()); }
    }
    @Override protected TaskState onTick() {
        try {
            if (!current()) return stop("chain_conveyor_endpoint_or_world_changed");
            if (!serverAssisted && !ServerAssistClient.nativeFallbackAllowed("machine.snapshot")) return stop("chain_conveyor_server_policy_denied");
            if (serverAssisted && !ServerAssistClient.serverSupported("machine.snapshot")) {
                if (ServerSessionRuntime.installed() && ServerAssistClient.renegotiating("machine.snapshot")) { stopNav(); InputDriver.halt(player); return TaskState.RUNNING; }
                return stop("chain_conveyor_server_snapshot_required");
            }
            if (creativeSupply != null) {
                var state = runChild(creativeSupply); if (state == null) return TaskState.RUNNING;
                var result = creativeSupply.result(state); creativeSupply = null;
                if (!result.success()) return stop("chain_conveyor_creative_tool_supply_failed");
                return TaskState.RUNNING;
            }
            if (supply.active()) {
                var state = NavigationSafetyContext.withPreservedStructures(List.of(r.first, r.second), () -> supply.tick(player, this::runChild));
                r.extendDeadlineTo(supply.childDeadline());
                if (state.status() == SemanticMaterialSupplyCoordinator.Status.FAILED) return stop("chain_conveyor_material_supply_failed: " + state.message());
                return TaskState.RUNNING;
            }
            return switch (phase) {
                case MATERIALS -> materials();
                case EQUIP -> equip();
                case FIRST_READ -> observeBefore(true);
                case SECOND_READ -> observeBefore(false);
                case FIRST_CLICK -> click(true);
                case SECOND_CLICK -> click(false);
                case FIRST_CONFIRM -> confirm(true);
                case SECOND_CONFIRM -> confirm(false);
                case VERIFY_SECOND -> verify(false);
                case VERIFY_FIRST -> verify(true);
                case RESTORE -> restore();
            };
        } catch (IllegalArgumentException | IllegalStateException problem) { return stop(problem.getMessage()); }
    }
    private boolean current() {
        return player.level() == world && r.dimension.equals(world.dimension().location().toString()) && player.isAlive() && player.mayBuild()
                && player.getAbilities().instabuild == creativeExempt
                && world.isLoaded(r.first) && world.isLoaded(r.second) && world.getBlockEntity(r.first) == firstEntity && world.getBlockEntity(r.second) == secondEntity
                && !NavigationSafetyContext.protectsUse(r.first) && !NavigationSafetyContext.protectsUse(r.second);
    }
    private void validatePair() {
        ChainConveyorGeometry.validate(r.first, r.second, ChainConveyorBridge.limits(world));
        ChainConveyorGeometry.clearEnvelope(world, r.first, r.second);
        var limits = ChainConveyorBridge.limits(world);
        if (firstBefore.size() >= limits.maximumConnections() || secondBefore.size() >= limits.maximumConnections())
            throw new IllegalArgumentException("chain_conveyor_connection_limit_reached");
        unchanged();
        geometryChecked = true;
    }
    private void unchanged() {
        if (!ChainConveyorBridge.connections(firstEntity).equals(firstBefore) || !ChainConveyorBridge.connections(secondEntity).equals(secondBefore))
            throw new IllegalArgumentException("chain_conveyor_links_changed_before_submission");
    }
    private boolean linked() {
        return ChainConveyorBridge.connections(firstEntity).contains(r.second.subtract(r.first))
                && ChainConveyorBridge.connections(secondEntity).contains(r.first.subtract(r.second));
    }
    private TaskState materials() {
        ChainConveyorInventory.requirePlainChains(player);
        int offhand = ChainConveyorInventory.count(player) - ChainConveyorInventory.mainCount(player);
        int requiredMain = creativeExempt ? 1 : Math.max(1, cost - offhand);
        if (ChainConveyorInventory.mainCount(player) < requiredMain) {
            if (creativeExempt && r.allowedSources.isEmpty() && r.materialPolicy != SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY) {
                creativeSupply = TaskFactory.create(player, new org.maiwithu.maicraft.core.task.inventory.CreativeTakeItemsTaskRecord(
                        r.getToolCallId() + "-chain-tool", r.getDeadlineGameTime(), new ItemStack(Items.CHAIN), 1));
                return TaskState.RUNNING;
            }
            supply.begin(player, r.getToolCallId(), r.getDeadlineGameTime(), new SemanticMaterialSupplyCoordinator.Demand(
                    List.of(ResourceLocation.parse("minecraft:chain")), requiredMain, "native chain-conveyor link"), r.materialPolicy, r.allowedSources, r.allowHarm, r.protectedLabels);
            return TaskState.RUNNING;
        }
        phase = Phase.EQUIP; return TaskState.RUNNING;
    }
    private TaskState equip() {
        if (!inventory.select(ClientRuntime.requireContext(player))) return TaskState.RUNNING;
        ChainConveyorInventory.requirePlainChains(player); countBefore = ChainConveyorInventory.count(player);
        if (countBefore < (creativeExempt ? 1 : cost)) return stop("chain_conveyor_materials_changed");
        phase = Phase.FIRST_READ; return TaskState.RUNNING;
    }
    private TaskState observeBefore(boolean first) {
        BlockPos target = first ? r.first : r.second;
        if (!near(target)) return TaskState.RUNNING;
        if (!serverAssisted) { unchanged(); phase = first ? Phase.FIRST_CLICK : Phase.SECOND_CLICK; return TaskState.RUNNING; }
        JsonObject observed = reads.snapshot(target, r.dimension); if (observed == null) return TaskState.RUNNING;
        ChainConveyorGeometry.validate(r.first, r.second, ChainConveyorRead.limits(observed));
        if (first) firstAdmission = observed;
        unchanged();
        if (ChainConveyorRead.hasLink(observed, first ? r.second : r.first)) return stop("chain_conveyor_server_link_changed_before_submission");
        phase = first ? Phase.FIRST_CLICK : Phase.SECOND_CLICK; return TaskState.RUNNING;
    }
    private TaskState click(boolean first) {
        validatePair(); ChainConveyorInventory.requirePlainChains(player);
        if (ChainConveyorInventory.count(player) != countBefore || !ChainConveyorInventory.plain(player.getMainHandItem()))
            return stop("chain_conveyor_held_chain_or_inventory_changed");
        var selected = ChainConveyorBridge.selection();
        if (first ? selected.first() != null : !selected.matches(world, r.first)) return stop("chain_conveyor_selection_changed_preserved");
        BlockPos target = first ? r.first : r.second;
        if (!near(target)) return TaskState.RUNNING;
        var context = ClientRuntime.requireContext(player);
        if (context.minecraft().screen != null || player.containerMenu != player.inventoryMenu) return stop("chain_conveyor_screen_busy");
        InputDriver.halt(player); InputDriver.sneak(player, false);
        if (player.isShiftKeyDown()) return TaskState.RUNNING;
        Vec3 point = ChainConveyorInteraction.aim(player, target, player.getEyePosition());
        if (point == null) return TaskState.RUNNING;
        InputDriver.lookAt(player, point);
        var hit = ChainConveyorInteraction.trace(player, player.getEyePosition(), player.getViewVector(1));
        if (hit == null || !hit.getBlockPos().equals(target) || !context.mutationAvailable()) return TaskState.RUNNING;
        if (!first && secondSubmitted) return stop("chain_conveyor_second_click_replay_blocked");
        if (first) selectedByTask = true; else secondSubmitted = true;
        action = context.actions().useBlock(context, InteractionHand.MAIN_HAND, hit, fresh -> {
            try {
                if (!current()) return NativeConfirmation.Verdict.DIVERGED;
                if (first) return ChainConveyorBridge.selection().matches(world, r.first) && ChainConveyorInventory.count(player) == countBefore
                        ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING;
                int carried = ChainConveyorInventory.count(player);
                int expected = countBefore - (creativeExempt ? 0 : cost);
                if (carried != countBefore && carried != expected) return NativeConfirmation.Verdict.DIVERGED;
                return linked() && carried == expected && ChainConveyorBridge.selection().first() == null
                        ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING;
            } catch (RuntimeException changed) { return NativeConfirmation.Verdict.DIVERGED; }
        }, 100);
        phase = first ? Phase.FIRST_CONFIRM : Phase.SECOND_CONFIRM; return TaskState.RUNNING;
    }
    private TaskState confirm(boolean first) {
        InputDriver.halt(player); action = ClientRuntime.requireContext(player).actions().poll(ClientRuntime.requireContext(player), action);
        if (!action.terminal()) return TaskState.RUNNING;
        if (action.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) return stop("chain_conveyor_native_click_unconfirmed_no_replay");
        action = null;
        if (first) firstSelectionConfirmed = true;
        if (!first) { effectSettled = true; countAfter = ChainConveyorInventory.count(player); }
        phase = first ? Phase.SECOND_READ : Phase.VERIFY_SECOND; return TaskState.RUNNING;
    }
    private TaskState verify(boolean first) {
        if (!linked()) return stop("chain_conveyor_native_link_not_reciprocal");
        BlockPos at = first ? r.first : r.second;
        if (!near(at)) return TaskState.RUNNING;
        JsonObject proof = serverAssisted ? reads.snapshot(at, r.dimension) : ChainConveyorRead.clientSnapshot(world, at);
        if (proof == null) return TaskState.RUNNING;
        if (serverAssisted && !ChainConveyorRead.hasLink(proof, first ? r.second : r.first)) return stop("chain_conveyor_server_link_missing");
        firstKinetic = ChainConveyorBridge.kinetic(world, r.first); secondKinetic = ChainConveyorBridge.kinetic(world, r.second);
        firstProof = first ? proof : ChainConveyorRead.clientSnapshot(world, r.first);
        secondProof = first ? ChainConveyorRead.clientSnapshot(world, r.second) : proof;
        if (serverAssisted) { serverObservedEndpoint = at; bothServerVerified = ChainConveyorRead.peerVerified(proof, first ? r.second : r.first); }
        verified = true; phase = Phase.RESTORE;
        return TaskState.RUNNING;
    }
    private boolean near(BlockPos target) {
        if (ChainConveyorInteraction.aim(player, target, player.getEyePosition()) != null) { stopNav(); navigating = null; approachTicks = 0; return true; }
        if (!target.equals(navigating)) { stopNav(); navigating = target; approachTicks = 0; }
        if (nav == null) {
            BlockPos stance = ChainConveyorInteraction.stance(player, target);
            nav = PlayerNav.to(player, () -> GoalCompiler.standOn(stance), .9,
                    () -> ChainConveyorInteraction.aim(player, target, player.getEyePosition()) != null, PlayerNav.ContextProvider.DEFAULT);
        }
        var state = NavigationSafetyContext.withPreservedStructures(List.of(r.first, r.second), nav::tick);
        if (state == PlayerNav.Status.FAILED || state == PlayerNav.Status.ARRIVED && ++approachTicks > 20)
            throw new IllegalArgumentException("chain_conveyor_endpoint_unreachable");
        return false;
    }
    private TaskState restore() {
        restored = inventory.restore(ClientRuntime.requireContext(player)); return restored ? TaskState.SUCCESS : TaskState.RUNNING;
    }
    private TaskState stop(String code) { failure = code; fail(code, FailureType.UNKNOWN); return TaskState.FAILED; }
    @Override protected void cleanup() {
        if (creativeSupply != null) { creativeSupply.stop(player, StopReason.REPLACED); creativeSupply.result(TaskState.CANCELLED); creativeSupply = null; }
        try { ownedSelectionPending = selectedByTask && ChainConveyorBridge.selection().matches(world, r.first); } catch (RuntimeException ignored) { }
        if (action != null && !action.terminal()) {
            var context = ClientRuntime.actor().activeContext().orElse(null);
            if (context != null) context.actions().retireOneShotForTaskBoundary(context, action, "chain connection outcome retained; never replay second click");
        }
        reads.cancel(); supply.cancel(player); inventory.close(player); super.cleanup();
    }
    @Override public Map<String, Object> progress() { return Map.of("task", name(), "phase", phase.name().toLowerCase(), "chain_cost", cost, "link_verified", verified); }
    @Override protected Map<String, Object> resultData() {
        var result = new LinkedHashMap<String, Object>();
        result.put("chain_conveyor_link_verified", verified); result.put("native_link_verified", verified); result.put("no_change", noChange);
        result.put("server_verified", verified && bothServerVerified); result.put("server_endpoint_verified", verified && serverAssisted);
        result.put("client_observed", verified); result.put("native_creative_exempt", creativeExempt);
        result.put("chain_cost", cost); result.put("confirmed_chain_consumption", effectSettled ? countBefore - countAfter : 0);
        result.put("chain_item", "minecraft:chain"); result.put("chain_consumption_verified", effectSettled && !creativeExempt);
        result.put("placement_geometry_preflight_verified", geometryChecked); result.put("chain_selection_owned", firstSelectionConfirmed);
        result.put("required_new_chains", noChange || creativeExempt ? 0 : cost);
        result.put("outcome_uncertain", secondSubmitted && !effectSettled); result.put("second_click_replay_allowed", false);
        result.put("inventory_restored", restored); result.put("owned_selection_pending", ownedSelectionPending);
        result.put("power_verified", false); result.put("production_verified", false);
        result.put("verification_scope", bothServerVerified ? "server_native_bidirectional_link" : serverAssisted
                ? "server_local_endpoint_plus_client_synchronized_peer" : "client_synchronized_links_and_native_inventory_effects");
        if (firstKinetic != null) result.put("first_rotation_observed", firstKinetic); if (secondKinetic != null) result.put("second_rotation_observed", secondKinetic);
        if (firstProof != null) result.put(r.first.equals(serverObservedEndpoint) ? "first_native_snapshot" : "first_client_observation", firstProof);
        if (secondProof != null) result.put(r.second.equals(serverObservedEndpoint) ? "second_native_snapshot" : "second_client_observation", secondProof);
        if (firstAdmission != null) result.put("first_pre_action_authorization_snapshot", firstAdmission);
        if (failure != null) result.put("failure_code", failure);
        return result;
    }
    @Override protected String successMessage() { return "Chain-conveyor link observed without a return trip; the receipt distinguishes server and client evidence, material effects, and inventory restoration from power and production."; }
}
