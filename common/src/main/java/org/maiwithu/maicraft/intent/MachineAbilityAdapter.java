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
import org.maiwithu.maicraft.core.integration.machine.MachineConstructionPlan;
import org.maiwithu.maicraft.core.integration.machine.MachineBuildTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocument;
import org.maiwithu.maicraft.core.integration.machine.layout.SemanticMachineLayout;
import org.maiwithu.maicraft.core.integration.ponder.PonderBlueprintStore;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.task.TaskResult;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 把“查看、设计、操作、修改或建造机器”交给相应实现；用户只给目标，具体放置和菜单点击由 Mod 负责。 */
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
        // 先按操作种类检查参数，再实际查看或创建任务；缺条件时给出明确问题，不直接开工。
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

    /** 计划阶段也按具体操作检查字段，例如“开菜单”与“取物品”需要的参数不同，不能传了又被忽略。 */
    static void validate(Goal goal) {
        JsonObject p = goal.parameters();
        switch (goal.ability()) {
            case INSPECT -> {
                only(p, "label", "radius", "structure_id", "component_offset", "resource_offset");
                optionalString(p, "label", 160);
                integer(p, "radius", 4, 0, 8);
                integer(p, "component_offset", 0, 0, 768);
                integer(p, "resource_offset", 0, 0, 4096);
                if (p.has("structure_id")) {
                    UUID.fromString(requiredString(p,"structure_id",36));
                    if (goal.target()!=null || p.has("radius") || p.has("component_offset") || p.has("resource_offset")) throw bad("structure_id inspects that whole observed physical structure; omit target, radius and paging offsets");
                } else if (goal.target() == null) throw bad("inspect_machine requires a semantic target or observed structure_id");
            }
            case DESIGN -> {
                // 通用设计可以没有场地；如果指定某处机器，就要求带上那处机器的观察编号。
                only(p, "design", "blueprint", "blueprint_uri", "snapshot_id", "production");
                validateLayoutSource(p, true);
                if (p.has("production")) MachineProductionIntent.validate(p);
                if (p.has("snapshot_id")) {
                    requiredString(p, "snapshot_id", 36);
                    requireMachineTarget(goal);
                } else if (goal.target() != null) throw bad("A site-specific design review needs snapshot_id from a machine/site inspection");
            }
            case OPERATE -> {
                String operation = requiredString(p, "operation", 64);
                switch (operation) {
                    case "run_production" -> {
                        only(p, "operation", "snapshot_id", "production", "allow_use", "protected_labels", "material_policy");
                        requiredString(p, "snapshot_id", 36);
                        bool(p, "allow_use", false);
                        MachineProductionIntent.validate(p);
                        if (p.has("material_policy")) SemanticMaterialSupplyCoordinator.MaterialPolicy.parse(requiredString(p, "material_policy", 64));
                        requireMachineTarget(goal);
                    }
                    case "watch_production" -> {
                        only(p,"operation","snapshot_id","production","allow_use","minimum_process_events","idle_ticks","max_duration_ticks");
                        requiredString(p,"snapshot_id",36); MachineProductionIntent.validate(p); bool(p,"allow_use",false);
                        watchLimits(p); requireMachineTarget(goal);
                    }
                    case "cancel_watch" -> {
                        only(p,"operation","job_id","allow_use"); UUID.fromString(requiredString(p,"job_id",36)); bool(p,"allow_use",false);
                        if (goal.target() != null) throw bad("cancel_watch binds its job_id; omit target");
                    }
                    case "drive_vehicle" -> {
                        only(p,"operation","structure_id","allow_use");
                        UUID.fromString(requiredString(p,"structure_id",36));
                        bool(p,"allow_use",false);
                        if(goal.target()==null || !Set.of("coordinates","landmark","area","prior_result").contains(goal.target().kind()))
                            throw bad("drive_vehicle requires an explicit destination target and an observed structure_id");
                    }
                    case "close_menu" -> {
                        // 关的是本流程现在打开的菜单，不能借这个操作顺便点名另一台机器。
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
                        // 取物或放物绑定刚才看过的菜单及其中一项，不能一边引用菜单、一边另选机器。
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
                        // 此分支要求 nearest 不带名称；外层 PublicToolCatalog 目前却要求 nearest 带名称，公开请求会冲突。
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
                    default -> throw bad("unsupported_machine_operation: choose run_production, watch_production, cancel_watch, drive_vehicle, set_control, open_menu, close_menu, deposit, withdraw or ae2_supply");
                }
            }
            case MODIFY -> {
                // 修改目前分为连接动力和按蓝图改方块，两种操作各自接受不同参数。
                String operation = requiredString(p, "operation", 64);
                if ("connect_mechanical_power".equals(operation)) {
                    only(p, "operation", "snapshot_id", "source_label", "allow_modify");
                    requiredString(p, "source_label", 160);
                } else if ("connect_external_input".equals(operation)) {
                    only(p,"operation","snapshot_id","source_label","input_id","allow_modify","material_policy","protected_labels");
                    requiredString(p,"source_label",160); requiredString(p,"input_id",64);
                    SemanticMaterialSupplyCoordinator.MaterialPolicy.parse(optionalString(p,"material_policy",64));
                } else if ("apply_blueprint".equals(operation)) {
                    only(p, "operation", "snapshot_id", "blueprint", "blueprint_uri", "allow_modify",
                            "material_policy", "replace_existing", "replace_block_entities", "protected_labels");
                    validateLayoutSource(p, false);
                    validateConstructionOptions(p);
                } else {
                    throw bad("unsupported_machine_modification: choose apply_blueprint, connect_mechanical_power or connect_external_input; click scripts are not accepted");
                }
                requiredString(p, "snapshot_id", 36);
                bool(p, "allow_modify", false);
                requireMachineTarget(goal);
            }
            case BUILD -> {
                only(p, "snapshot_id", "design", "blueprint", "blueprint_uri", "allow_modify", "material_policy", "replace_existing", "replace_block_entities", "protected_labels", "production", "allow_use");
                requiredString(p, "snapshot_id", 36);
                validateLayoutSource(p, true);
                validateSeparateUtilityConstruction(p);
                if (p.has("production")) { MachineProductionIntent.validate(p); bool(p, "allow_use", false); }
                else if (p.has("allow_use")) throw bad("allow_use on build_machine requires an explicit production goal");
                bool(p, "allow_modify", false);
                validateConstructionOptions(p);
                requireMachineTarget(goal);
            }
            default -> { }
        }
    }

    private static IntentAction inspect(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        if (goal.parameters().has("structure_id")) {
            var inspection=org.maiwithu.maicraft.core.integration.machine.control.MachineControlInspection.structure(player,
                    UUID.fromString(goal.parameters().get("structure_id").getAsString()));
            return new IntentAction.Report(TaskResult.ok("Physical structure control paths inspected; see verified connections, unresolved inputs and vehicle classification.",
                    Map.of("machine",inspection.report())),null);
        }
        // 在指定位置读机器周围的方块并给观察结果一个编号，同时记住机器的地点名；命名不等于允许修改。
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
        org.maiwithu.maicraft.core.integration.machine.catalog.ClientMachineCatalog.inspected(player,label,center,radius);
        return new IntentAction.Native(new org.maiwithu.maicraft.client.server.ServerMachineObservationTaskRecord(
                "machine-inspection-" + UUID.randomUUID(), player.level().getGameTime() + 1_200, snapshot,
                integer(p, "component_offset", 0, 0, 768), integer(p, "resource_offset", 0, 0, 4096)));
    }

    private static IntentAction design(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        // 组件关系图交给后台算布局，明确蓝图则直接检查；报告里的“审阅完成”不表示设计一定能建成。
        JsonObject p = goal.parameters();
        var layout = compileLayout(p, player);
        if (layout == null) return IntentAction.Pending.INSTANCE;
        MachineSnapshots.Snapshot snapshot = goal.parameters().has("snapshot_id")
                ? boundSnapshot(goal, player, runtime) : null;
        JsonObject report = p.has("design") ? MachineDesignReview.review(p.getAsJsonObject("design"),
                id -> registered(id, true), id -> registered(id, false)) : new JsonObject();
        report.addProperty("review_kind", p.has("design") ? "semantic_design" : "blueprint");
        if (p.has("blueprint_uri")) report.addProperty("blueprint_uri", p.get("blueprint_uri").getAsString());
        if (p.has("design") && report.getAsJsonObject("validation").get("valid").getAsBoolean()
                && p.getAsJsonObject("design").has("expected_output")) {
            report.add("recipe_evidence", MachineRecipeEvidence.inspect(player,
                    goal.parameters().getAsJsonObject("design").get("expected_output").getAsString()));
        }
        if (snapshot != null) {
            JsonObject context = new JsonObject();
            context.addProperty("snapshot_id", snapshot.id());
            context.addProperty("label", snapshot.label());
            context.addProperty("structure_fingerprint", snapshot.fingerprint());
            context.addProperty("scope", "fresh site identity; structure requires construction verification and operation requires separate use evidence");
            report.add("observed_context", context);
        }
        report.add("layout_compiler", layout.report());
        if (p.has("production")) report.add("production", MachineProductionIntent.review(p.getAsJsonObject("production")));
        // 返回成功只是完成了检查，调用者还要看报告中哪些条件未满足，不能据此说机器已建好。
        return new IntentAction.Report(TaskResult.ok("Machine design review completed; inspect validation and unresolved obligations before proposing work.",
                Map.of("design_review", report)), null);
    }

    private static IntentAction operate(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        // 真正开关、存取或用 AE2 前检查 allow_use；这里只创建任务单，后续在游戏中等待实际效果确认。
        JsonObject p = goal.parameters();
        if (!bool(p, "allow_use", false)) throw bad("machine_use_not_authorized: set allow_use only when the player's instructions authorize this operation; marking another player's machine is not permission");
        long deadline = player.level().getGameTime() + 3 * 60 * 20;
        String callId = "machine-" + UUID.randomUUID();
        String operation = requiredString(p, "operation", 64);
        if ("cancel_watch".equals(operation)) {
            var result = org.maiwithu.maicraft.client.server.ClientMachineWatches.cancel(player,UUID.fromString(requiredString(p,"job_id",36)));
            return new IntentAction.Report(TaskResult.ok("后台观察取消已请求；机器本身不会被关闭。",Map.of("monitor",result)),null);
        }
        if ("watch_production".equals(operation)) {
            if (!org.maiwithu.maicraft.client.server.ServerAssistClient.supported("machine.watch")) throw bad("machine_watch_requires_server_support");
            MachineSnapshots.Snapshot snapshot = boundSnapshot(goal,player,runtime);
            var plan = new org.maiwithu.maicraft.core.integration.machine.runtime.ProductionRunPlan(snapshot.center(),snapshot.dimension(),p.getAsJsonObject("production"));
            WatchLimits limits = watchLimits(p);
            var task = new org.maiwithu.maicraft.core.integration.machine.runtime.MachineWatchTaskRecord(callId,player.level().getGameTime()+6000,
                    snapshot.label(),plan,limits.minimumProcessEvents(),limits.durationTicks(),limits.idleTicks());
            MachineSnapshots.consume(snapshot); return new IntentAction.Native(task);
        }
        if ("run_production".equals(operation)) {
            MachineProductionIntent.requireRuntime(p.getAsJsonObject("production"));
            MachineSnapshots.Snapshot snapshot = boundSnapshot(goal, player, runtime);
            var plan = new org.maiwithu.maicraft.core.integration.machine.runtime.ProductionRunPlan(
                    snapshot.center(), snapshot.dimension(), p.getAsJsonObject("production"));
            org.maiwithu.maicraft.core.integration.machine.catalog.ClientMachineCatalog.registerPlan(player,snapshot.label(),plan);
            var protections = new java.util.LinkedHashSet<>(goal.inheritedProtectionLabels());
            if (p.has("protected_labels")) p.getAsJsonArray("protected_labels").forEach(value -> protections.add(value.getAsString()));
            var task = new org.maiwithu.maicraft.core.integration.machine.runtime.MachineProductionTaskRecord(
                    callId, player.level().getGameTime() + 45L * 60 * 20, plan, null, List.copyOf(protections),
                    p.has("material_policy") ? SemanticMaterialSupplyCoordinator.MaterialPolicy.parse(p.get("material_policy").getAsString())
                            : SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY);
            MachineSnapshots.consume(snapshot);
            return new IntentAction.Native(task);
        }
        if ("drive_vehicle".equals(operation)) {
            var destination=resolve(goal.target(),player,runtime);
            return new IntentAction.Native(new org.maiwithu.maicraft.core.integration.machine.control.VehicleDriveTaskRecord(
                    callId,player.level().getGameTime()+15*60*20,UUID.fromString(requiredString(p,"structure_id",36)),
                    new net.minecraft.world.phys.Vec3(destination.x(),destination.y(),destination.z())));
        }
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
            // AE2 由原生终端接口取物；允许合成时也只能提交网络里已经有的合成样板。
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
                // component_index 指的是这份观察列表里的第几块，换另一份观察就不能沿用同一个数字。
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
        // 应用蓝图与新建共用施工流程；连接动力则创建专门的 Create 任务，并保留原网络。
        JsonObject p = goal.parameters();
        if (!bool(p, "allow_modify", false)) throw bad("machine_modification_not_authorized: set allow_modify when the player's instructions authorize this change");
        if ("apply_blueprint".equals(p.get("operation").getAsString())) return build(goal, player, runtime);
        if ("connect_external_input".equals(p.get("operation").getAsString())) {
            MachineSnapshots.Snapshot snapshot = boundSnapshot(goal,player,runtime);
            var installation = org.maiwithu.maicraft.core.integration.machine.catalog.ClientMachineCatalog.requireInstallation(player,snapshot.center());
            String inputId = requiredString(p,"input_id",64), sourceLabel = requiredString(p,"source_label",160);
            var input = installation.inputs().stream().filter(value -> value.id().equals(inputId)).findFirst()
                    .orElseThrow(() -> bad("machine_external_input_unknown: " + inputId));
            BlockPos source = block(resolve(new Goal.SemanticTarget("landmark",sourceLabel,null,null),player,runtime));
            var request = new org.maiwithu.maicraft.core.integration.machine.utility.UtilityConnectionTaskRecord.Request(
                    source,snapshot.center().offset(input.offset()),input.face(),input.blockId(),input.medium(),
                    input.minimumRpm() == null ? 0 : input.minimumRpm(),0,input.resource());
            var protections = new java.util.LinkedHashSet<>(goal.inheritedProtectionLabels());
            if (p.has("protected_labels")) p.getAsJsonArray("protected_labels").forEach(value -> protections.add(value.getAsString()));
            var task = new org.maiwithu.maicraft.core.integration.machine.utility.UtilityConnectionTaskRecord(
                    "machine-utility-"+UUID.randomUUID(),player.level().getGameTime()+15L*60*20,snapshot.dimension(),sourceLabel,inputId,request,
                    SemanticMaterialSupplyCoordinator.MaterialPolicy.parse(optionalString(p,"material_policy",64)),List.copyOf(protections));
            MachineSnapshots.consume(snapshot); return new IntentAction.Native(task);
        }
        // 如果上次留下了可恢复编号，使用它核对原来的连接，避免重复装已经放好的部分；否则需要新观察。
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
        // 布局算完后再核对场地观察是否仍有效；图形方案、允许替换哪些方块和供料策略一起交给施工任务。
        JsonObject p = goal.parameters();
        if (!bool(p, "allow_modify", false)) throw bad("machine_build_not_authorized: the player's instructions must authorize building this machine");
        if (p.has("production")) {
            if (!bool(p, "allow_use", false)) throw bad("machine_production_not_authorized: allow_use is required to run the declared production chain");
            MachineProductionIntent.requireRuntime(p.getAsJsonObject("production"));
        }
        var layout = compileLayout(p, player);
        if (layout == null) return IntentAction.Pending.INSTANCE;
        if (p.has("production") && !org.maiwithu.maicraft.core.integration.machine.utility.MachineUtilityInputs.parse(layout.blueprint()).isEmpty())
            throw bad("external_utility_connection_is_separate: build without production, connect_external_input, then run_production");
        MachineSnapshots.Snapshot snapshot = boundSnapshot(goal, player, runtime);
        if (layout.buildable()) {
            boolean replace = bool(p, "replace_existing", false);
            JsonObject design = p.getAsJsonObject("design");
            if (replace && design != null && design.has("constraints") && design.getAsJsonObject("constraints").has("preserve_existing")
                    && design.getAsJsonObject("constraints").get("preserve_existing").getAsBoolean())
                throw bad("replace_existing conflicts with the design's preserve_existing constraint");
            BlockPos anchor = design == null ? snapshot.center() : MachineConstructionPlan.floorAnchor(snapshot.center(), layout);
            // 明确蓝图的偏移从观察中心算；自动生成布局则先换算地板锚点，两类输入的定位规则不同。
            var plan = MachineConstructionPlan.compile(anchor, layout, replace, bool(p, "replace_block_entities", false));
            if (!org.maiwithu.maicraft.core.integration.machine.catalog.ClientMachineCatalog.registerInstallation(player,snapshot.label(),plan))
                return IntentAction.Pending.INSTANCE;
            List<String> protectedLabels = p.has("protected_labels")
                    ? java.util.stream.StreamSupport.stream(p.getAsJsonArray("protected_labels").spliterator(), false)
                        .map(com.google.gson.JsonElement::getAsString).toList() : List.of();
            long deadline = player.level().getGameTime() + Math.max(45L * 60 * 20,
                    (long) (plan.blocks().size() + plan.parts().size()) * 100);
            var task = new MachineBuildTaskRecord("machine-" + UUID.randomUUID(), deadline, plan,
                    snapshot.dimension(), SemanticMaterialSupplyCoordinator.MaterialPolicy.parse(
                            optionalString(p, "material_policy", 64)), protectedLabels);
            org.maiwithu.maicraft.task.TaskRecord execution = task;
            if (p.has("production")) execution = new org.maiwithu.maicraft.core.integration.machine.runtime.MachineProductionTaskRecord(
                    task.getToolCallId(), deadline,
                    new org.maiwithu.maicraft.core.integration.machine.runtime.ProductionRunPlan(anchor,
                            snapshot.dimension(), p.getAsJsonObject("production")), task, protectedLabels);
            if (execution instanceof org.maiwithu.maicraft.core.integration.machine.runtime.MachineProductionTaskRecord production)
                org.maiwithu.maicraft.core.integration.machine.catalog.ClientMachineCatalog.registerPlan(player,snapshot.label(),production.plan);
            MachineSnapshots.consume(snapshot);
            // 一份观察只用于发起一次修改，即使后面的施工失败，也要重新观察才可另开一份修改任务。
            return new IntentAction.Native(execution);
        }
        JsonObject context = new JsonObject();
        context.addProperty("ability", goal.ability());
        context.addProperty("failure_code", "machine_layout_unresolved");
        context.addProperty("snapshot_id", snapshot.id());
        context.addProperty("target_label", snapshot.label());
        context.add("layout_compiler", layout.report());
        context.addProperty("boundary", "Supply a corrected semantic design or explicit blueprint. MaiCraft owns build order, routes and native gestures; click scripts are unsupported. Construction verifies declared structure; use verifies operation separately.");
        return new IntentAction.Decision(new IntentTaskRecord.DecisionSnapshot(UUID.randomUUID(),
                "The machine layout could not be compiled. Inspect the reported issues and revise the design or blueprint.",
                List.of(
                        new IntentTaskRecord.DecisionOption("replace_goal", "Correct the blueprint, select another tutorial structure, or revise the semantic design."),
                        new IntentTaskRecord.DecisionOption("cancel", "Leave the observed site unchanged.")),
                context.toString()));
    }

    private static void validateLayoutSource(JsonObject p, boolean allowDesign) {
        // 布局来源只能三选一：组件关系图、完整蓝图或已导出的教程蓝图地址；禁止同时给多份让执行端猜。
        int count = (p.has("design") ? 1 : 0) + (p.has("blueprint") ? 1 : 0) + (p.has("blueprint_uri") ? 1 : 0);
        if (count != 1 || (!allowDesign && p.has("design")))
            throw bad(allowDesign ? "Supply exactly one of design, blueprint or blueprint_uri"
                    : "Supply exactly one of blueprint or blueprint_uri");
        if (p.has("design")) {
            if (!p.get("design").isJsonObject()) throw bad("design must be a semantic component graph object");
            validateSemanticDesign(p.getAsJsonObject("design"));
        } else if (p.has("blueprint")) {
            if (!p.get("blueprint").isJsonObject()) throw bad("blueprint must be a versioned structure object");
            MachineBlueprintDocument.validateWire(p.getAsJsonObject("blueprint"));
        } else {
            String uri = requiredString(p, "blueprint_uri", 2048);
            String prefix = "maicraft://knowledge/ponder/structure/";
            if (!uri.startsWith(prefix) || uri.length() == prefix.length())
                throw bad("blueprint_uri must be an exact exported Ponder structure resource URI");
        }
    }

    private static void validateConstructionOptions(JsonObject p) {
        // 允许拆带数据的机器／箱子，必须先允许普通替换，不能把两个许可写成互相矛盾的组合。
        boolean replace = bool(p, "replace_existing", false);
        if (bool(p, "replace_block_entities", false) && !replace)
            throw bad("replace_block_entities requires replace_existing=true");
        SemanticMaterialSupplyCoordinator.MaterialPolicy.parse(optionalString(p, "material_policy", 64));
    }

    private static SemanticMachineLayout.Result compileLayout(JsonObject p, LocalPlayer player) {
        // 关系图需要计算具体布局；蓝图已经给出每格目标，只需按方块规则解析和检查。
        SemanticMachineLayout.Result result;
        if (p.has("design")) result = MachineLayoutJobs.poll(player, p.getAsJsonObject("design"));
        else {
            JsonObject blueprint = p.has("blueprint") ? p.getAsJsonObject("blueprint")
                    : PonderBlueprintStore.resolve(requiredString(p, "blueprint_uri", 2048));
            result = MachineConstructionPlan.reviewExplicit(MachineBlueprintDocument.compile(blueprint, MachineConstructionPlan.registry()));
        }
        return result == null ? null : org.maiwithu.maicraft.core.integration.machine.utility.MachineSurvivalMaterials.requireSurvivalBlueprint(player,result);
    }

    private static void validateSeparateUtilityConstruction(JsonObject p) {
        if (!p.has("production")) return;
        JsonObject shape = p.has("blueprint") ? p.getAsJsonObject("blueprint") : p.has("design") ? p.getAsJsonObject("design") : null;
        if (shape != null && shape.has("external_inputs") && !shape.getAsJsonArray("external_inputs").isEmpty())
            throw bad("external_utility_connection_is_separate: build without production, connect_external_input, then run_production");
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
        // 同时核对观察编号、机器名字和位置，不能用甲机器的观察去授权修改乙机器。
        MachineSnapshots.Snapshot snapshot = MachineSnapshots.requireFresh(player,
                requiredString(goal.parameters(), "snapshot_id", 36));
        Goal.WorldPosition target = resolve(goal.target(), player, runtime);
        if (!snapshot.center().equals(block(target)) || !snapshot.label().equalsIgnoreCase(goal.target().label())) {
            throw bad("machine_snapshot_target_mismatch: use the exact machine label measured by this snapshot");
        }
        return snapshot;
    }

    private static void requireMachineTarget(Goal goal) {
        // 大多数机器操作只能引用已记住的地点名，不接受再叠加坐标或关系描述。
        if (goal.target() == null || !Set.of("landmark", "area").contains(goal.target().kind())
                || goal.target().label() == null || goal.target().label().isBlank()
                || goal.target().position() != null || goal.target().relation() != null) {
            throw bad("This machine operation requires one remembered machine label as target");
        }
    }

    private static Goal.WorldPosition resolve(Goal.SemanticTarget target, LocalPlayer player, IntentRuntime runtime) {
        // 读取当前地点、坐标或已记地标，确认属于当前维度；找不到时不随便找附近另一台机器代替。
        if (target == null) throw bad("machine_target_missing");
        String dimension = player.level().dimension().location().toString();
        Goal.WorldPosition resolved = switch (target.kind()) {
            case "current_place" -> new Goal.WorldPosition(player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ(), dimension);
            case "coordinates" -> target.position();
            case "landmark", "area" -> {
                var place = runtime.landmark(target.label());
                yield place == null ? null : place.position();
            }
            default -> null;
        };
        if (resolved == null) throw bad("machine_target_unresolved: identify or remember the intended place first");
        if (resolved.dimension() != null && !dimension.equals(resolved.dimension())) throw bad("machine_target_wrong_dimension");
        return new Goal.WorldPosition(resolved.x(), resolved.y(), resolved.z(), dimension);
    }

    private static BlockPos block(Goal.WorldPosition p) { return new BlockPos(p.x(), p.y(), p.z()); }
    private static boolean registered(String value, boolean block) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        return id != null && !"minecraft:air".equals(value)
                && (block ? BuiltInRegistries.BLOCK.containsKey(id) : BuiltInRegistries.ITEM.containsKey(id));
    }
    private static void only(JsonObject p, String... keys) {
        Set<String> allowed = Set.of(keys);
        for (String key : p.keySet()) if (!allowed.contains(key)) throw bad("Unsupported field for this machine operation: " + key);
    }
    private static String optionalString(JsonObject p, String key, int max) {
        if (!p.has(key)) return null;
        return requiredString(p, key, max);
    }
    private static String requiredString(JsonObject p, String key, int max) {
        if (!p.has(key) || !p.get(key).isJsonPrimitive() || !p.getAsJsonPrimitive(key).isString()) throw bad(key + " must be a string");
        String value = p.get(key).getAsString();
        if (value.isBlank() || value.length() > max) throw bad(key + " must contain 1.." + max + " characters");
        return value;
    }
    private static boolean bool(JsonObject p, String key, Boolean fallback) {
        if (!p.has(key)) {
            if (fallback != null) return fallback;
            throw bad(key + " is required");
        }
        if (!p.get(key).isJsonPrimitive() || !p.getAsJsonPrimitive(key).isBoolean()) throw bad(key + " must be boolean");
        return p.get(key).getAsBoolean();
    }
    record WatchLimits(int minimumProcessEvents, int durationTicks, int idleTicks) {}
    static WatchLimits watchLimits(JsonObject p) {
        int events = integer(p,"minimum_process_events",1,1,100);
        int duration = integer(p,"max_duration_ticks",72000,20,72000);
        // The default must fit a short observation; explicit values retain strict bounds.
        int idle = integer(p,"idle_ticks",Math.min(6000,duration),20,duration);
        return new WatchLimits(events,duration,idle);
    }
    private static int integer(JsonObject p, String key, int fallback, int min, int max) {
        // 机器参数使用精确整数转换，拒绝小数和溢出，然后再检查范围；不会默默把数值压到边界。
        if (!p.has(key)) return fallback;
        try {
            if (!p.get(key).isJsonPrimitive() || !p.getAsJsonPrimitive(key).isNumber()) throw bad(key + " must be an integer");
            int value = p.get(key).getAsBigDecimal().intValueExact();
            if (value < min || value > max) throw bad(key + " must be in " + min + ".." + max);
            return value;
        } catch (ArithmeticException | NumberFormatException invalid) { throw bad(key + " must be an integer in " + min + ".." + max); }
    }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
