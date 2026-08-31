// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;
import org.maiwithu.maicraft.core.integration.create.CreateMechanicalPower;
import org.maiwithu.maicraft.core.integration.machine.MachineControl;
import org.maiwithu.maicraft.core.integration.machine.MachineDesignReview;
import org.maiwithu.maicraft.core.integration.machine.MachineMenu;
import org.maiwithu.maicraft.core.integration.machine.MachineRecipeEvidence;
import org.maiwithu.maicraft.core.integration.machine.MachineSnapshots;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.task.TaskResult;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Semantic machine evidence and bounded, verified native operations; no model-authored clicks. */
final class MachineAbilityAdapter {
    static final String INSPECT = "maicraft:inspect_machine";
    static final String DESIGN = "maicraft:design_machine";
    static final String OPERATE = "maicraft:operate_machine";
    static final String MODIFY = "maicraft:modify_machine";
    static final String BUILD = "maicraft:build_machine";
    private static final Set<String> ABILITIES = Set.of(INSPECT, DESIGN, OPERATE, MODIFY, BUILD);

    private MachineAbilityAdapter() {}
    static boolean supports(String ability) { return ABILITIES.contains(ability); }

    static IntentAction adapt(Goal goal, LocalPlayer player, IntentRuntime runtime, UUID continuationToken) {
        try {
            validate(goal);
            return switch (goal.ability()) {
                case INSPECT -> inspect(goal, player, runtime);
                case DESIGN -> design(goal, player, runtime);
                case OPERATE -> operate(goal, player, runtime);
                case MODIFY -> modify(goal, player, runtime, continuationToken);
                case BUILD -> build(goal, player, runtime);
                default -> throw new IllegalArgumentException("unknown machine ability");
            };
        } catch (IllegalArgumentException unavailable) {
            JsonObject context = new JsonObject();
            context.addProperty("ability", goal.ability());
            context.addProperty("failure_code", "machine_precondition_failed");
            return new IntentAction.Decision(new IntentTaskRecord.DecisionSnapshot(UUID.randomUUID(),
                    unavailable.getMessage(), List.of(
                    new IntentTaskRecord.DecisionOption("replace_goal", "Supply a corrected semantic goal or inspect fresh machine evidence."),
                    new IntentTaskRecord.DecisionOption("cancel", "Cancel this task.")), context.toString()));
        }
    }

    /** Validate operation-specific contracts at plan time, including silently ignored fields. */
    static void validate(Goal goal) {
        JsonObject p = goal.parameters();
        switch (goal.ability()) {
            case INSPECT -> {
                only(p, "label", "radius");
                optionalString(p, "label", 160);
                integer(p, "radius", 4, 0, 8);
                if (goal.target() == null) throw bad("inspect_machine requires an explicit semantic target");
            }
            case DESIGN -> {
                only(p, "design", "snapshot_id");
                if (!p.has("design") || !p.get("design").isJsonObject()) throw bad("design must be a component graph object");
                validateSemanticDesign(p.getAsJsonObject("design"));
                if (p.has("snapshot_id")) {
                    requiredString(p, "snapshot_id", 36);
                    requireMachineTarget(goal);
                } else if (goal.target() != null) throw bad("A site-specific design review needs snapshot_id from a machine/site inspection");
            }
            case OPERATE -> {
                String operation = requiredString(p, "operation", 64);
                switch (operation) {
                    case "close_menu" -> {
                        only(p, "operation", "allow_use");
                        bool(p, "allow_use", false);
                        if (goal.target() != null) throw bad("close_menu acts on this workflow's currently open menu; omit target");
                    }
                    case "open_menu" -> {
                        only(p, "operation", "snapshot_id", "component_index", "allow_use");
                        requiredString(p, "snapshot_id", 36);
                        integer(p, "component_index", 0, 0, 767);
                        bool(p, "allow_use", false);
                        requireMachineTarget(goal);
                    }
                    case "deposit", "withdraw" -> {
                        only(p, "operation", "menu_receipt_id", "entry_index", "item_id", "count", "allow_use");
                        requiredString(p, "menu_receipt_id", 36);
                        if (!p.has("entry_index")) throw bad("entry_index must name an entry in the latest machine_menu observation");
                        integer(p, "entry_index", 0, 0, 511);
                        requiredString(p, "item_id", 256);
                        integer(p, "count", 1, 1, 64);
                        bool(p, "allow_use", false);
                        if (goal.target() != null) throw bad("Menu transactions bind the exact observed menu_receipt_id; omit target rather than selecting another machine");
                    }
                    case "set_control" -> {
                        only(p, "operation", "snapshot_id", "control_label", "powered", "allow_use");
                        requiredString(p, "snapshot_id", 36);
                        optionalString(p, "control_label", 160);
                        bool(p, "powered", null);
                        bool(p, "allow_use", false);
                        requireMachineTarget(goal);
                    }
                    case "ae2_supply" -> {
                        only(p, "operation", "item_id", "count", "allow_crafting", "allow_use");
                        requiredString(p, "item_id", 256);
                        integer(p, "count", 1, 1, 256);
                        bool(p, "allow_crafting", false);
                        bool(p, "allow_use", false);
                        if (goal.target() == null || !"nearest".equals(goal.target().kind())
                                || goal.target().label() != null || goal.target().position() != null
                                || goal.target().relation() != null) {
                            throw bad("ae2_supply requires target={kind:nearest}: it uses a natively accessible terminal, not a selected surveyed network");
                        }
                    }
                    default -> throw bad("unsupported_machine_operation: choose set_control, open_menu, close_menu, deposit, withdraw or ae2_supply");
                }
            }
            case MODIFY -> {
                String operation = requiredString(p, "operation", 64);
                if ("connect_mechanical_power".equals(operation)) {
                    only(p, "operation", "snapshot_id", "source_label", "allow_modify");
                    requiredString(p, "source_label", 160);
                } else {
                    throw bad("unsupported_machine_modification: use the dedicated high-level operation connect_mechanical_power; exact cells, block states and click scripts are not accepted");
                }
                requiredString(p, "snapshot_id", 36);
                bool(p, "allow_modify", false);
                requireMachineTarget(goal);
            }
            case BUILD -> {
                only(p, "snapshot_id", "design", "allow_modify");
                requiredString(p, "snapshot_id", 36);
                if (!p.has("design") || !p.get("design").isJsonObject()) throw bad("design must be a semantic component graph object");
                validateSemanticDesign(p.getAsJsonObject("design"));
                bool(p, "allow_modify", false);
                requireMachineTarget(goal);
            }
            default -> { }
        }
    }

    private static IntentAction inspect(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        Goal.WorldPosition position = resolve(goal.target(), player, runtime);
        JsonObject p = goal.parameters();
        String label = optionalString(p, "label", 160);
        if (label == null) label = goal.target().label();
        if (label == null || label.isBlank()) throw bad("Give the machine a short label so subsequent analysis and operation refer to the same place");
        int radius = integer(p, "radius", 4, 0, 8);
        BlockPos center = block(position);
        if (!player.level().isLoaded(center)) throw bad("machine_anchor_unloaded: travel closer before inspecting; unloaded terrain is not empty space");
        MachineSnapshots.Snapshot snapshot = MachineSnapshots.inspect(player, label, center, radius);
        runtime.remember(label, position);
        return new IntentAction.Report(TaskResult.ok("Machine structure observed; inferred connections and unknown state require analysis.",
                Map.of("machine", snapshot.report())), position);
    }

    private static IntentAction design(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        MachineSnapshots.Snapshot snapshot = goal.parameters().has("snapshot_id")
                ? boundSnapshot(goal, player, runtime) : null;
        JsonObject report = MachineDesignReview.review(goal.parameters().getAsJsonObject("design"),
                id -> registered(id, true), id -> registered(id, false));
        if (report.getAsJsonObject("validation").get("valid").getAsBoolean()
                && goal.parameters().getAsJsonObject("design").has("expected_output")) {
            report.add("recipe_evidence", MachineRecipeEvidence.inspect(player,
                    goal.parameters().getAsJsonObject("design").get("expected_output").getAsString()));
        }
        if (snapshot != null) {
            JsonObject context = new JsonObject();
            context.addProperty("snapshot_id", snapshot.id());
            context.addProperty("label", snapshot.label());
            context.addProperty("structure_fingerprint", snapshot.fingerprint());
            context.addProperty("scope", "fresh site identity; graph roles/connections remain proposals until separately verified");
            report.add("observed_context", context);
            JsonObject compiler = new JsonObject();
            compiler.addProperty("supported", false);
            compiler.addProperty("code", "semantic_machine_layout_compiler_unavailable");
            compiler.addProperty("message", "The site is observed, but no Mod-side compiler currently turns this arbitrary semantic graph into exact placements. Use a dedicated high-level machine ability when one exists; do not provide cells, offsets, block states or clicks.");
            report.add("layout_compiler", compiler);
        }
        // Completing a review never asserts that the design is valid or that it was constructed.
        return new IntentAction.Report(TaskResult.ok("Machine design review completed; inspect validation and unresolved obligations before proposing work.",
                Map.of("design_review", report)), null);
    }

    private static IntentAction operate(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        JsonObject p = goal.parameters();
        if (!bool(p, "allow_use", false)) throw bad("machine_use_not_authorized: set allow_use only when the player's instructions authorize this operation; marking another player's machine is not permission");
        long deadline = player.level().getGameTime() + 3 * 60 * 20;
        String callId = "machine-" + UUID.randomUUID();
        String operation = requiredString(p, "operation", 64);
        if ("close_menu".equals(operation)) return new IntentAction.Native(MachineMenu.closeTask(callId, deadline));
        if (Set.of("deposit", "withdraw").contains(operation)) {
            String item = requiredString(p, "item_id", 256);
            if (!registered(item, false)) throw bad("unknown requested item: " + item);
            return new IntentAction.Native(MachineMenu.transferTask(callId, deadline,
                    requiredString(p, "menu_receipt_id", 36), operation,
                    integer(p, "entry_index", 0, 0, 511), ResourceLocation.parse(item),
                    integer(p, "count", 1, 1, 64)));
        }
        if ("ae2_supply".equals(requiredString(p, "operation", 64))) {
            if (!Ae2ResourceSupply.available()) throw bad("AE2 native terminal integration unavailable: " + Ae2ResourceSupply.availabilityDetail());
            String item = requiredString(p, "item_id", 256);
            if (!registered(item, false)) throw bad("unknown requested item: " + item);
            var request = new Ae2ResourceSupply.Request(List.of(new Ae2ResourceSupply.Group(
                    ResourceLocation.parse(item), integer(p, "count", 1, 1, 256))),
                    bool(p, "allow_crafting", false));
            return new IntentAction.Native(Ae2ResourceSupply.taskRecord(callId, deadline, request));
        }
        MachineSnapshots.Snapshot snapshot = boundSnapshot(goal, player, runtime);
        if ("open_menu".equals(operation)) {
            BlockPos machine = snapshot.center();
            if (p.has("component_index")) {
                int index = integer(p, "component_index", 0, 0, 767);
                var observed = snapshot.report().getAsJsonArray("relative_blocks");
                if (index >= observed.size()) throw bad("component_index is not present in this machine observation");
                var offset = observed.get(index).getAsJsonArray();
                machine = snapshot.center().offset(offset.get(0).getAsInt(), offset.get(1).getAsInt(), offset.get(2).getAsInt());
            }
            var nativeRecord = MachineMenu.openTask(callId, deadline, new MachineMenu.OpenRequest(
                    snapshot.dimension(), snapshot.center(), snapshot.radius(), snapshot.fingerprint(), machine));
            MachineSnapshots.consume(snapshot);
            return new IntentAction.Native(nativeRecord);
        }
        String controlLabel = optionalString(p, "control_label", 160);
        BlockPos control = controlLabel == null ? null : block(resolve(
                new Goal.SemanticTarget("landmark", controlLabel, null, null), player, runtime));
        var request = new MachineControl.Request(snapshot.dimension(), snapshot.center(), snapshot.radius(),
                snapshot.fingerprint(), bool(p, "powered", null), control);
        var nativeRecord = MachineControl.task(callId, deadline, request);
        MachineSnapshots.consume(snapshot);
        return new IntentAction.Native(nativeRecord);
    }

    private static IntentAction modify(Goal goal, LocalPlayer player, IntentRuntime runtime, UUID continuationToken) {
        JsonObject p = goal.parameters();
        if (!bool(p, "allow_modify", false)) throw bad("machine_modification_not_authorized: set allow_modify when the player's instructions authorize this change");
        // A native continuation is already bound to the original route, body and endpoints.
        // Its receipt replaces the consumed survey; the Create executor revalidates it before work.
        MachineSnapshots.Snapshot snapshot = continuationToken == null ? boundSnapshot(goal, player, runtime) : null;
        BlockPos destination = snapshot == null ? block(resolve(goal.target(), player, runtime)) : snapshot.center();
        String sourceLabel = requiredString(p, "source_label", 160);
        BlockPos source = block(resolve(new Goal.SemanticTarget("landmark", sourceLabel, null, null), player, runtime));
        if (!CreateMechanicalPower.availability().available()) throw bad("Create kinetic integration unavailable: " + CreateMechanicalPower.availability().detail());
        var request = CreateMechanicalPower.Request.preserving(
                new CreateMechanicalPower.Endpoint(sourceLabel, source),
                new CreateMechanicalPower.Endpoint(goal.target().label(), destination));
        String callId = "machine-" + UUID.randomUUID();
        long deadline = player.level().getGameTime() + 3 * 60 * 20;
        var nativeRecord = continuationToken == null
                ? CreateMechanicalPower.task(callId, deadline, request,
                    SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY, List.of(), false, List.of())
                : CreateMechanicalPower.resumeTask(callId, deadline, request,
                    SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY, List.of(), false, List.of(), continuationToken);
        if (snapshot != null) MachineSnapshots.consume(snapshot);
        return new IntentAction.Native(nativeRecord);
    }

    private static IntentAction build(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        JsonObject p = goal.parameters();
        if (!bool(p, "allow_modify", false)) throw bad("machine_build_not_authorized: the player's instructions must authorize building this machine");
        MachineSnapshots.Snapshot snapshot = boundSnapshot(goal, player, runtime);
        JsonObject review = MachineDesignReview.review(p.getAsJsonObject("design"),
                id -> registered(id, true), id -> registered(id, false));
        if (!review.getAsJsonObject("validation").get("valid").getAsBoolean()) {
            throw bad("machine_design_invalid: inspect design_machine validation errors before requesting construction");
        }
        JsonObject context = new JsonObject();
        context.addProperty("ability", BUILD);
        context.addProperty("failure_code", "semantic_machine_layout_compiler_unavailable");
        context.addProperty("snapshot_id", snapshot.id());
        context.addProperty("target_label", snapshot.label());
        context.add("design_review", review);
        context.addProperty("boundary", "The Mod must derive exact positions, states, build order, routes and native gestures. Supplying a blueprint, cells, offsets or clicks is not a recovery option.");
        return new IntentAction.Decision(new IntentTaskRecord.DecisionSnapshot(UUID.randomUUID(),
                "This arbitrary machine design has no matching Mod-side layout compiler yet. Use a supported dedicated high-level ability, or add a native compiler for this machine family; MaiCraft will not ask the LLM for block-by-block instructions.",
                List.of(
                        new IntentTaskRecord.DecisionOption("replace_goal", "Choose an available semantic ability such as connect_mechanical_power, or request an outcome that already has a Mod-side planner."),
                        new IntentTaskRecord.DecisionOption("cancel", "Leave the observed site unchanged.")),
                context.toString()));
    }

    private static void validateSemanticDesign(JsonObject design) {
        JsonObject review = MachineDesignReview.review(design, ignored -> true, ignored -> true);
        if (review.getAsJsonObject("validation").get("valid").getAsBoolean()) return;
        JsonObject first = review.getAsJsonObject("validation").getAsJsonArray("errors")
                .get(0).getAsJsonObject();
        throw bad("invalid semantic machine design at " + first.get("path").getAsString()
                + ": " + first.get("message").getAsString());
    }

    private static MachineSnapshots.Snapshot boundSnapshot(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        MachineSnapshots.Snapshot snapshot = MachineSnapshots.requireFresh(player,
                requiredString(goal.parameters(), "snapshot_id", 36));
