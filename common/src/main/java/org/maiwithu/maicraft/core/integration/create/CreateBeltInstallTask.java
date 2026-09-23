// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.integration.machine.assembly.AssemblyBlockAim;
import org.maiwithu.maicraft.core.integration.machine.assembly.AssemblyInteractionGeometry;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.container.ContainerSupplySources;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.TaskState;

/** 准备连接器 -> 点击第一轴 -> 确认端点标记 -> 点击第二轴 -> 核对整段与消耗；任何未确认点击都不重放。 */
final class CreateBeltInstallTask extends AbstractCompanionTask<CreateBeltInstallTaskRecord> {
    private enum Phase { EQUIP, FIRST, FIRST_CONFIRM, SECOND, SECOND_CONFIRM, DONE }
    private final FirstPersonActionGate selection = new FirstPersonActionGate();
    private final Level world;
    private final boolean creative;
    private final Map<BlockPos, Object> identities = new LinkedHashMap<>();
    private Phase phase = Phase.EQUIP;
    private NativeActionReceipt action;
    private BeltLinkReceipt evidence;
    private BlockPos navigating;
    private int source = -1, countBefore, countAfter;
    private boolean firstSubmitted, secondSubmitted, verified, noChange, pendingSelection;
    private String failure;
    CreateBeltInstallTask(LocalPlayer player, CreateBeltInstallTaskRecord record) {
        super(player, record); world = player.level(); creative = player.hasInfiniteMaterials();
    }
    @Override protected void onStart() {
        try {
            CreateBeltGeometry.between(r.span.first(), r.span.second(), r.span.axis(), CreateBeltAccess.maximumLength());
            if (CreateBeltAccess.matches(world, r.span, r.pulleys)) { verified = noChange = true; phase = Phase.DONE; return; }
            validatePrepared();
            for (BlockPos at : r.pulleys) identities.put(at, world.getBlockEntity(at));
            for (int slot = 0; slot < player.getInventory().items.size(); slot++)
                if (CreateBeltAccess.unselected(player.getInventory().getItem(slot))) { source = slot; break; }
            if (source < 0) stop("belt_plain_unselected_connector_missing");
        } catch (RuntimeException unavailable) { stop("belt_preflight_failed: " + unavailable.getMessage()); }
    }
    @Override protected TaskState onTick() {
        if (failure != null) return TaskState.FAILED;
        if (verified) return TaskState.SUCCESS;
        if (player.level() != world || !player.isAlive() || !player.mayBuild() || player.hasInfiniteMaterials() != creative)
            return stop("belt_body_or_world_changed");
        try {
            return switch (phase) {
                case EQUIP -> equip();
                case FIRST -> click(true);
                case FIRST_CONFIRM -> confirm(true);
                case SECOND -> click(false);
                case SECOND_CONFIRM -> confirm(false);
                case DONE -> TaskState.SUCCESS;
            };
        } catch (RuntimeException changed) { return stop("belt_native_state_unavailable: " + changed.getMessage()); }
    }
    private void validatePrepared() {
        for (BlockPos at : r.span.cells()) {
            if (!world.isLoaded(at) || NavigationSafetyContext.protectsMutation(at)
                    || !ContainerSupplySources.accessAllowed(player, at, r.protectedLabels))
                throw new IllegalArgumentException("belt_footprint_unloaded_or_protected");
            var state = world.getBlockState(at);
            if (!state.getFluidState().isEmpty()) throw new IllegalArgumentException("belt_requires_dry_prepared_span");
            if (r.pulleys.contains(at) ? !CreateBeltAccess.shaft(state, r.span) : !state.isAir())
                throw new IllegalArgumentException("belt_prepared_cells_changed");
        }
        if (!CreateBeltAccess.canConnect(world, r.span)) throw new IllegalArgumentException("belt_native_connection_rejected");
        for (var identity : identities.entrySet()) if (world.getBlockEntity(identity.getKey()) != identity.getValue())
            throw new IllegalArgumentException("belt_prepared_shaft_replaced");
    }
    private TaskState equip() {
        var status = selection.select(player, source);
        if (status == FirstPersonActionGate.Status.FAILED) return stop("belt_connector_equip_failed: " + selection.failure());
        if (status != FirstPersonActionGate.Status.READY) return TaskState.RUNNING;
        if (!CreateBeltAccess.unselected(player.getMainHandItem())) return stop("belt_foreign_selection_preserved");
        countBefore = CreateBeltAccess.count(player); evidence = new BeltLinkReceipt(r.span.first(), countBefore, creative);
        phase = Phase.FIRST; return TaskState.RUNNING;
    }
    private TaskState click(boolean first) {
        validatePrepared();
        if (CreateBeltAccess.count(player) != countBefore || !CreateBeltAccess.connector(player.getMainHandItem()))
            return stop("belt_connector_inventory_changed");
        BlockPos selected = CreateBeltAccess.selection(player.getMainHandItem());
        if (first ? selected != null : !r.span.first().equals(selected)) return stop("belt_connector_selection_changed");
        BlockPos target = first ? r.span.first() : r.span.second();
        if (!near(target)) return TaskState.RUNNING;
        var context = ClientRuntime.requireContext(player);
        if (context.minecraft().screen != null || player.containerMenu != player.inventoryMenu) return stop("belt_foreign_menu_open");
        if (!context.mutationAvailable() || player.getCooldowns().isOnCooldown(player.getMainHandItem().getItem())) return TaskState.RUNNING;
        InputDriver.halt(player); InputDriver.sneak(player, false);
        if (player.isShiftKeyDown()) return TaskState.RUNNING;
        var point = AssemblyBlockAim.point(player, target, player.getEyePosition());
        if (point == null) return TaskState.RUNNING;
        InputDriver.lookAt(player, point);
        var hit = AssemblyBlockAim.trace(player, player.getEyePosition(), player.getViewVector(1));
        if (hit == null || !hit.getBlockPos().equals(target)) return TaskState.RUNNING;
        if (first ? firstSubmitted : secondSubmitted) return stop("belt_native_click_replay_blocked");
        if (first) firstSubmitted = true; else secondSubmitted = true;
        action = context.actions().useBlock(context, InteractionHand.MAIN_HAND, hit, fresh -> {
            if (fresh.player() != player || fresh.level() != world) return NativeConfirmation.Verdict.DIVERGED;
            try {
                int count = CreateBeltAccess.count(player);
                BlockPos marker = CreateBeltAccess.selection(player.getMainHandItem());
                return first ? evidence.first(count, marker) : evidence.second(count, marker, CreateBeltAccess.matches(world, r.span, r.pulleys));
            } catch (RuntimeException unavailable) { return NativeConfirmation.Verdict.DIVERGED; }
        }, 100);
        phase = first ? Phase.FIRST_CONFIRM : Phase.SECOND_CONFIRM;
        return TaskState.RUNNING;
    }
    private TaskState confirm(boolean first) {
        var context = ClientRuntime.requireContext(player); action = context.actions().poll(context, action);
        if (!action.terminal()) return TaskState.RUNNING;
        if (action.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) return stop("belt_native_click_unconfirmed_no_replay");
        action = null;
        if (first) { phase = Phase.SECOND; }
        else { countAfter = CreateBeltAccess.count(player); verified = true; phase = Phase.DONE; }
        return verified ? TaskState.SUCCESS : TaskState.RUNNING;
    }
    private boolean near(BlockPos target) {
        if (AssemblyBlockAim.point(player, target, player.getEyePosition()) != null) { stopNav(); return true; }
        if (!target.equals(navigating)) { stopNav(); navigating = target; }
        if (nav == null) {
            BlockPos stance = AssemblyInteractionGeometry.nearestStand(player, target, Set.of(),
                    eye -> AssemblyBlockAim.point(player, target, eye), Pose.STANDING);
            if (stance == null) throw new IllegalArgumentException("belt_endpoint_has_no_visible_stance");
            nav = PlayerNav.to(player, () -> GoalCompiler.standOn(stance), 1,
                    () -> AssemblyBlockAim.point(player, target, player.getEyePosition()) != null, PlayerNav.ContextProvider.DEFAULT);
        }
        if (NavigationSafetyContext.withPreservedStructures(r.installation, nav::tick) == PlayerNav.Status.FAILED)
            throw new IllegalArgumentException("belt_endpoint_unreachable");
        return false;
    }
    private TaskState stop(String code) { failure = code; fail(code, FailureType.UNKNOWN); return TaskState.FAILED; }
    @Override protected void cleanup() {
        // 停止任务后不补发第二次连接；本任务留下的端点标记会进入回执，外来标记绝不清除。
        try { pendingSelection = firstSubmitted && !verified && r.span.first().equals(CreateBeltAccess.selection(player.getMainHandItem())); }
        catch (RuntimeException unavailable) { pendingSelection = firstSubmitted && !verified; }
        if (action != null && !action.terminal()) {
            var context = ClientRuntime.actor().activeContext().orElse(null);
            if (context != null) context.actions().retireOneShotForTaskBoundary(context, action, "belt installation ended; never replay connector clicks");
        }
        selection.reset(); InputDriver.halt(player); super.cleanup();
    }
    @Override public boolean mustSettleBeforeSatisfiedCancellation() { return firstSubmitted && !verified; }
    @Override public Map<String, Object> progress() { return Map.of("task", name(), "phase", phase.name().toLowerCase(), "native_link_verified", verified); }
    @Override protected Map<String, Object> resultData() {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("native_link_verified", verified); result.put("no_change", noChange);
        result.put("connector_consumed", verified && !noChange ? countBefore - countAfter : 0);
        result.put("connector_selection_pending", pendingSelection); result.put("effects_started", firstSubmitted);
        result.put("outcome_uncertain", !verified && firstSubmitted);
        result.put("effects_settled", verified || !firstSubmitted);
        result.put("world_change_uncertain", secondSubmitted && !verified);
        if (failure != null) result.put("failure_code", failure);
        return result;
    }
    @Override protected String successMessage() { return "传送带整段原生结构与连接器消耗已确认。"; }
}
