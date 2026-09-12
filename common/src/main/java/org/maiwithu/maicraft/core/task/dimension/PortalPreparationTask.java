// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.core.task.structure.PhysicalStructureSearchTaskRecord;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** Observe -> obtain materials -> build/repair -> use native items -> verify the actual portal. */
public final class PortalPreparationTask extends AbstractCompanionTask<PortalPreparationTaskRecord> {
    private enum Phase { SURVEY, SUPPLY, RETURN, BUILD, LOCATE, MOVE, ACTIVATE, VERIFY }
    private final ClientLevel world;
    private final java.util.function.BiFunction<LocalPlayer, TaskRecord, Task> childFactory;
    private PortalSiteSurvey survey;
    private PortalPreparationSite site;
    private Phase phase = Phase.SURVEY;
    private Task child;
    private TaskRecord childRecord;
    private PortalPreparationSupplies.Need supplyNeed;
    private BlockPos supplyOrigin, activationTarget, activationStance;
    private PortalActivation activation;
    private Vec3 activationAim;
    private List<BlockPos> protectedLabels = List.of();
    private Map<String, Object> childEvidence = Map.of();
    private boolean end, searchedStronghold, complete, cleaned;
    private long verificationDeadline = -1;
    private String issue;
    private int serial;

    public PortalPreparationTask(LocalPlayer player, PortalPreparationTaskRecord record) {
        this(player, record, TaskFactory::create);
    }
    PortalPreparationTask(LocalPlayer player, PortalPreparationTaskRecord record,
                          java.util.function.BiFunction<LocalPlayer, TaskRecord, Task> childFactory) {
        super(player, record); world = player.clientLevel; this.childFactory = childFactory;
    }

    @Override protected void onStart() {
        if (!r.policy.enabled()) { blocked("portal_preparation_permission_required", "Enable prepare_portal to prepare a portal."); return; }
        String current = world.dimension().location().toString();
        end = "minecraft:the_end".equals(r.destination) && "minecraft:overworld".equals(current);
        boolean nether = "minecraft:the_nether".equals(r.destination) && "minecraft:overworld".equals(current)
                || "minecraft:overworld".equals(r.destination) && "minecraft:the_nether".equals(current);
        if (!end && !nether) { blocked("portal_preparation_unsupported", "This route has no constructible vanilla portal; End exit portals require the dragon encounter."); return; }
        var protection = new ArrayList<BlockPos>();
        for (String label : r.policy.protectedLabels()) {
            var landmark = IntentRuntime.get().landmarks().stream().filter(l -> label.equalsIgnoreCase(l.label())).findFirst().orElse(null);
            if (landmark == null || landmark.position() == null) { blocked("protected_area_unknown", "Unknown protected place: " + label); return; }
            var p = landmark.position();
            if (p.dimension() == null || p.dimension().equals(current)) protection.add(new BlockPos(p.x(), p.y(), p.z()));
        }
        protectedLabels = List.copyOf(protection);
        survey = new PortalSiteSurvey(world, player.blockPosition(), r.radius, end);
    }

    @Override protected TaskState onTick() {
        if (player.level() != world) return blocked("portal_world_changed", "The preparation world changed; inspect before resuming.");
        return NavigationSafetyContext.withProtectedArea(protectedLabels, List.of(), this::advance);
    }

    private TaskState advance() {
        if (child != null) return tickChild();
        if (activation != null) {
            TaskState state = activation.tick(player);
            if (state == TaskState.RUNNING) return state;
            String failure = activation.failure(); activation.close(player); activation = null;
            if (state != TaskState.SUCCESS) return blocked("portal_activation_unconfirmed", failure);
            activationTarget = null; activationStance = null; verificationDeadline = -1;
        }
        if (site == null) return survey();
        if (!site.valid(world)) return blocked("portal_site_changed", "The portal frame or interior changed, unloaded or became protected.");
        if (site.active(world)) { complete = true; return TaskState.SUCCESS; }
        if (!site.missingBlocks(world).isEmpty() && !r.mayAlterTerrain)
            return blocked("portal_construction_permission_required", "Building or repairing the Nether frame needs may_alter_terrain=true.");
        if (end && !r.policy.allowRareConsumables() && !site.end().missingEyes(p -> PortalPreparationSite.read(world, p)).isEmpty())
            return blocked("rare_consumable_permission_required", "Inserting End portal eyes needs allow_rare_consumables=true.");
        var need = PortalPreparationSupplies.next(player, site);
        if (need != null) return supply(need);
        if (!site.missingBlocks(world).isEmpty())
            return start(site.construction(world, callId(), deadline()), Phase.BUILD);
        if (end && site.end().missingEyes(p -> PortalPreparationSite.read(world, p)).isEmpty()) {
            phase = Phase.VERIFY;
            if (verificationDeadline < 0) verificationDeadline = world.getGameTime() + 100;
            return world.getGameTime() < verificationDeadline ? TaskState.RUNNING
                    : blocked("portal_surface_not_observed", "All eyes are present but the full active portal surface was not observed.");
        }
        return activate();
    }

    private TaskState survey() {
        phase = Phase.SURVEY;
        site = survey.tick(r.mayAlterTerrain);
        if (site != null) { survey.close(); return TaskState.RUNNING; }
        if (!survey.complete()) { r.extendDeadlineTo(r.getDeadlineGameTime() + 1); return TaskState.RUNNING; }
        if (!end) return blocked(r.mayAlterTerrain ? "portal_site_unavailable" : "portal_construction_permission_required",
                "No reusable frame or suitable loaded construction site was found; construction needs may_alter_terrain=true.");
        if (searchedStronghold) return blocked("end_portal_frame_incomplete", "No intact, correctly oriented End portal ring was observed after reaching the stronghold.");
        if (!r.policy.allowRareConsumables()) return blocked("rare_consumable_permission_required", "Finding a stronghold may consume Eyes of Ender.");
        var eyes = PortalPreparationSupplies.eyes(4);
        if (!eyes.satisfied(player)) return supply(eyes);
        searchedStronghold = true;
        return start(new PhysicalStructureSearchTaskRecord(callId(), deadline(), "minecraft:stronghold",
                r.policy.maxStructureDistance(), r.mayAlterTerrain, true, true), Phase.LOCATE);
    }

    private TaskState supply(PortalPreparationSupplies.Need need) {
        supplyNeed = need; supplyOrigin = player.blockPosition().immutable();
        return start(need.acquire(callId(), deadline(), r.policy, r.mayAlterTerrain), Phase.SUPPLY);
    }

    private TaskState activate() {
        if (activationTarget == null) {
            activationTarget = end ? site.end().missingEyes(p -> PortalPreparationSite.read(world, p)).stream()
                    .min(Comparator.comparingDouble(p -> p.distSqr(player.blockPosition()))).orElseThrow()
                    : site.nether().origin().below();
            activationAim = Vec3.atLowerCornerOf(activationTarget).add(.5, end ? .8125 : 1, .5);
            activationStance = PortalApproach.find(player, site, activationTarget, activationAim, Set.of());
            if (activationStance == null) return blocked("portal_activation_unreachable", "No safe exterior stance can see the required portal face.");
        }
        if (!player.blockPosition().equals(activationStance))
            return start(MoveToTaskRecord.strictStance(callId(), deadline(), activationStance, r.mayAlterTerrain), Phase.MOVE);
        phase = Phase.ACTIVATE;
        BlockPos target = activationTarget;
        var item = end ? Items.ENDER_EYE : PlayerInv.count(player.getInventory(), Items.FLINT_AND_STEEL) > 0
                ? Items.FLINT_AND_STEEL : Items.FIRE_CHARGE;
        activation = new PortalActivation(item, target, activationAim, Direction.UP,
                () -> player.blockPosition().equals(activationStance) && site.ready(world)
                        && (!end || !world.getBlockState(target).getValue(EndPortalFrameBlock.HAS_EYE)),
                () -> end ? site.end().intact(p -> PortalPreparationSite.read(world, p))
                        && world.getBlockState(target).getValue(EndPortalFrameBlock.HAS_EYE) : site.active(world));
        return TaskState.RUNNING;
    }

    private TaskState start(TaskRecord record, Phase next) {
        phase = next; childRecord = record;
        child = guarded(() -> childFactory.apply(player, record));
        r.extendDeadlineTo(record.getDeadlineGameTime());
        return TaskState.RUNNING;
    }
    private long deadline() { return Math.max(r.getDeadlineGameTime(), world.getGameTime() + 6000); }
    private String callId() { return r.getToolCallId() + "-portal-" + (++serial); }
    private <T> T guarded(java.util.function.Supplier<T> operation) {
        if (site == null) return operation.get();
        return NavigationSafetyContext.withForbiddenBodyCells(site.forbiddenBody(), () -> phase == Phase.BUILD
                ? operation.get() : NavigationSafetyContext.withPreservedStructures(site.footprint(), operation));
    }

    private TaskState tickChild() {
        TaskState terminal;
        if (world.getGameTime() >= childRecord.getDeadlineGameTime()) {
            guarded(() -> { child.stop(player, Task.StopReason.REPLACED); return null; }); terminal = TaskState.TIMEOUT;
        } else terminal = guarded(() -> runChild(child));
        r.extendDeadlineTo(childRecord.getDeadlineGameTime());
        if (terminal == null) return TaskState.RUNNING;
        TaskState settled = terminal;
        TaskResult result = guarded(() -> child.result(settled));
        child = null; childRecord = null;
        if (result == null) return blocked("portal_child_unconfirmed", "The preparation child ended without a result.");
        if (terminal != TaskState.SUCCESS || !result.success()) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            for (String key : List.of("issue_code", "allowed_dimensions", "recovery_options", "failure_type", "requires_narration", "target_item_family"))
                if (result.data() != null && result.data().containsKey(key)) evidence.put(key, result.data().get(key));
            childEvidence = Map.copyOf(evidence);
            String fallback = "requires_dimension".equals(evidence.get("failure_type")) ? "requires_dimension"
                    : "portal_" + phase.name().toLowerCase(java.util.Locale.ROOT) + "_failed";
            return blocked(evidence.getOrDefault("issue_code", fallback).toString(), result.message());
        }
        if (phase == Phase.SUPPLY) {
            if (!supplyNeed.satisfied(player)) return blocked("portal_supply_unverified", "Supply ended without the required inventory increase.");
            supplyNeed = null;
            return start(MoveToTaskRecord.strictStance(callId(), deadline(), supplyOrigin, r.mayAlterTerrain), Phase.RETURN);
        }
        if (phase == Phase.LOCATE) {
            survey.close(); survey = new PortalSiteSurvey(world, player.blockPosition(), r.radius, true);
        }
        if (phase == Phase.BUILD && !site.ready(world)) return blocked("portal_frame_unverified", "Construction ended without a complete live obsidian frame.");
        return TaskState.RUNNING;
    }

    private TaskState blocked(String code, String message) {
        issue = code; fail(message == null ? code : message, FailureType.TARGET_LOST); return TaskState.FAILED;
    }
    @Override protected Map<String, Object> resultData() {
        var data = new LinkedHashMap<String, Object>(childEvidence);
        data.put("portal_prepared", complete); data.put("preparation_phase", phase.name().toLowerCase(java.util.Locale.ROOT));
        if (issue != null) { data.put("issue_code", issue); data.put("requires_decision", true); }
        return data;
    }
    @Override public Map<String, Object> progress() {
        var data = new LinkedHashMap<>(resultData());
        if (child != null) data.put("child", child.progress()); return data;
    }
    @Override public void stop(LocalPlayer player, Task.StopReason why) {
        try {
            if (child != null) guarded(() -> { child.stop(player, why); return null; });
            if (activation != null) activation.close(player);
            super.stop(player, why);
        } finally { if (why != Task.StopReason.PREEMPTED) cleanup(); }
    }
    @Override protected void cleanup() {
        if (cleaned) return; cleaned = true;
        try {
            if (child != null) guarded(() -> { child.stop(player, Task.StopReason.REPLACED); child.result(TaskState.CANCELLED); return null; });
        } finally {
            child = null; childRecord = null;
            if (activation != null) activation.close(player);
            if (survey != null) survey.close();
            super.cleanup();
        }
    }
    @Override protected String successMessage() { return "portal preparation verified from the active portal surface"; }
}
