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
import org.maiwithu.maicraft.core.integration.machine.MachineSnapshotRejection;
import org.maiwithu.maicraft.core.integration.machine.MachineSnapshots;
import org.maiwithu.maicraft.core.integration.machine.MachineSurvey;
import org.maiwithu.maicraft.core.integration.create.DepotStationProcessTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.MachineInspectionBlueprintView;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineBlueprint;
import org.maiwithu.maicraft.core.integration.machine.MachineConstructionPlan;
import org.maiwithu.maicraft.core.integration.machine.MachineBuildTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocument;
import org.maiwithu.maicraft.core.integration.machine.layout.SemanticMachineLayout;
import org.maiwithu.maicraft.core.integration.ponder.PonderBlueprintStore;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.task.TaskResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.google.gson.JsonElement;
import java.util.LinkedHashSet;
import java.util.stream.StreamSupport;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.server.ClientMachineWatches;
import org.maiwithu.maicraft.client.server.ServerAssistClient;
import org.maiwithu.maicraft.client.server.ServerMachineObservationTaskRecord;
import org.maiwithu.maicraft.core.integration.create.transmission.EconomicKineticTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.catalog.ClientMachineCatalog;
import org.maiwithu.maicraft.core.integration.machine.control.MachineControlInspection;
import org.maiwithu.maicraft.core.integration.machine.control.VehicleDriveTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.runtime.MachineProductionTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.runtime.MachineWatchTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.runtime.ProductionRunPlan;
import org.maiwithu.maicraft.core.integration.machine.utility.MachineSurvivalMaterials;
import org.maiwithu.maicraft.core.integration.machine.utility.MachineUtilityInputs;
import org.maiwithu.maicraft.core.integration.machine.utility.UtilityConnectionTaskRecord;
import org.maiwithu.maicraft.mcp.knowledge.RecipeKnowledgeSource;
import org.maiwithu.maicraft.task.TaskRecord;

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
        } catch (MachineSnapshotRejection rejected) {
            // 旧现场或编号无法支持原生动作时，结束本次尝试并交付新现场，避免只给过期提示再等待勘测。
            return new IntentAction.Report(TaskResult.fail(rejected.getMessage(), rejected.details()), null);
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
                // 固定机器与移动结构共用只读入口；先校验字段形状，结构编号出现时再拒绝固定机器范围与分页参数。
                // 某些兼容参数在 full/diff 中不参与读取，具体有效范围由公开契约说明，不能把接受字段说成一定生效。
                only(p, "label", "radius", "structure_id", "component_offset", "resource_offset", "machine_id", "mode", "offset", "limit");
                optionalString(p, "label", 160);
                String mode = optionalString(p,"mode",16);
                if (mode != null && !Set.of("full","diff").contains(mode)) throw bad("inspect_machine mode must be full or diff");
                optionalString(p,"machine_id",80); integer(p,"offset",0,0,Integer.MAX_VALUE); integer(p,"limit",256,1,512);
                integer(p, "radius", 4, 0, 8);
                integer(p, "component_offset", 0, 0, 768);
                integer(p, "resource_offset", 0, 0, 4096);
                if (p.has("structure_id")) {
                    UUID.fromString(requiredString(p,"structure_id",36));
                    if (goal.target()!=null || p.has("radius") || p.has("component_offset") || p.has("resource_offset") || p.has("machine_id") || p.has("mode") || p.has("offset") || p.has("limit")) throw bad("structure_id inspects that whole observed physical structure; omit fixed-machine selectors and paging offsets");
                } else if (goal.target() == null && !p.has("machine_id")) throw bad("inspect_machine requires a semantic target, machine_id or observed structure_id");
            }
            case DESIGN -> {
                // 通用设计可以没有场地；如果指定某处机器，就要求带上那处机器的观察编号。
                only(p, "design", "blueprint", "blueprint_uri", "snapshot_id", "production");
                validateLayoutSource(p, true);
                if (p.has("production")) MachineProductionIntent.validate(p);
                MachineDesignBindings.validate(goal);
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
                        if (MachineProductionIntent.isNative(p.getAsJsonObject("production")) && p.has("material_policy")
                                && !"inventory_only".equals(p.get("material_policy").getAsString()))
                            throw bad("native_process_requires_carried_inputs: acquire_items separately, then use inventory_only");
                        requireMachineTarget(goal);
                    }
                    case "watch_production" -> {
                        only(p,"operation","snapshot_id","production","allow_use","minimum_process_events","idle_ticks","max_duration_ticks");
                        requiredString(p,"snapshot_id",36); MachineProductionIntent.validate(p); bool(p,"allow_use",false);
                        if (MachineProductionIntent.isNative(p.getAsJsonObject("production"))) throw bad("watch_production supports v1 machine networks only; native processes run in the foreground");
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
                        // 拉杆设到明确的开或关：带观察编号时沿用同址观察；不带时按目标就地划一个小范围找唯一拉杆。
                        only(p, "operation", "snapshot_id", "control_label", "powered", "allow_use");
                        optionalString(p, "control_label", 160);
                        bool(p, "powered", null);
                        bool(p, "allow_use", false);
                        if (p.has("snapshot_id")) {
                            requiredString(p, "snapshot_id", 36);
                            requireMachineTarget(goal);
                        } else if (goal.target() == null
                                || !Set.of("coordinates", "landmark", "area", "nearest").contains(goal.target().kind())) {
                            throw bad("set_control without snapshot_id needs target coordinates (the lever cell), "
                                    + "landmark/area (a remembered place or the exact text of one nearby sign) or nearest");
                        }
                    }
                    case "station_process" -> {
                        // 在现成置物台上加工：手持原料放上、等现场机器加工、空手收回；目标选一台置物台，不要观察编号。
                        only(p, "operation", "item_id", "max_wait_seconds", "allow_use");
                        requiredString(p, "item_id", 256);
                        integer(p, "max_wait_seconds", 30, 1, 300);
                        bool(p, "allow_use", false);
                        if (goal.target() == null
                                || !Set.of("coordinates", "landmark", "area", "nearest").contains(goal.target().kind()))
                            throw bad("station_process needs target coordinates (the depot cell), landmark/area "
                                    + "(a remembered place or the exact text of one nearby sign) or nearest");
                    }
                    case "ae2_supply" -> {
                        // 无名 nearest 表示使用原生可达终端；公开目标契约已允许这种形态，这里仍拒绝借名称选择另一网络。
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
                    default -> throw bad("unsupported_machine_operation: choose run_production, watch_production, cancel_watch, drive_vehicle, set_control, station_process, open_menu, close_menu, deposit, withdraw or ae2_supply");
                }
            }
            case MODIFY -> {
                // 结构补丁、兼容动力接线和登记外部入口分别使用自己的参数；不能把一种分支的材料或方向开关悄悄带进另一种。
                String operation = requiredString(p, "operation", 64);
                if ("connect_mechanical_power".equals(operation)) {
                    only(p, "operation", "snapshot_id", "source_label", "allow_modify");
                    requiredString(p, "source_label", 160);
                } else if ("connect_external_input".equals(operation)) {
                    only(p,"operation","snapshot_id","source_label","source_radius","input_id","allow_modify","material_policy","protected_labels");
                    optionalString(p,"source_label",160); requiredString(p,"input_id",64);
                    integer(p,"source_radius",64,8,128);
                    SemanticMaterialSupplyCoordinator.MaterialPolicy.parse(optionalString(p,"material_policy",64));
                } else if ("apply_blueprint".equals(operation)) {
                    only(p, "operation", "snapshot_id", "blueprint", "blueprint_uri", "allow_modify",
                            "material_policy", "replace_existing", "replace_block_entities", "protected_labels");
                    validateLayoutSource(p, false);
                    validateConstructionOptions(p, true);
                } else {
                    throw bad("unsupported_machine_modification: choose apply_blueprint, connect_mechanical_power or connect_external_input; click scripts are not accepted");
                }
                optionalString(p, "snapshot_id", 36);
                bool(p, "allow_modify", false);
                requireMachineTarget(goal);
            }
            case BUILD -> {
                only(p, "snapshot_id", "design", "blueprint", "blueprint_uri", "allow_modify", "material_policy", "replace_existing", "replace_block_entities", "protected_labels", "production", "allow_use");
                // 丢失场地编号只需补回观察给出的绑定，不为修正提交外壳重新派遣角色勘察机器。
                try { requiredString(p, "snapshot_id", 36); }
                catch (IllegalArgumentException invalid) {
                    throw bad(invalid.getMessage() + ". Copy parameters.snapshot_id and target from the same "
                            + "perceive(view=construction_site) result; reuse an existing site observation, or obtain one if none exists.");
                }
                validateLayoutSource(p, true);
                validateSeparateUtilityConstruction(p);
                if (p.has("production")) { MachineProductionIntent.validate(p); bool(p, "allow_use", false); }
                else if (p.has("allow_use")) throw bad("allow_use on build_machine requires an explicit production goal");
                bool(p, "allow_modify", false);
                validateConstructionOptions(p, false);
                requireMachineTarget(goal);
            }
            default -> { }
        }
    }

    private static IntentAction inspect(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        // 移动结构先读真实控制回路；固定机器按选址读取现状和整机差异，只有已加载 full 才继续补原生组件页。
        // 观察不派角色走近或开菜单，成功只表示取得可用事实，不能推断机器可工作。
        if (goal.parameters().has("structure_id")) {
            var inspection=MachineControlInspection.structure(player,
                    UUID.fromString(goal.parameters().get("structure_id").getAsString()));
            return new IntentAction.Report(TaskResult.ok("Physical structure control paths inspected; see verified connections, unresolved inputs and vehicle classification.",
                    Map.of("machine",inspection.report())),null);
        }
        JsonObject p = goal.parameters();
        // 显式机器编号优先确定档案和位置；当前即使同时给了 target 也不会用它选址，调用方应只用一种入口。
        MachineBlueprint saved = p.has("machine_id") ? ClientMachineCatalog.blueprint(player,p.get("machine_id").getAsString(),null)
                .orElseThrow(() -> bad("machine_record_not_found")) : null;
        Goal.WorldPosition position = saved == null ? resolve(goal.target(), player, runtime)
                : new Goal.WorldPosition(saved.anchor().x(),saved.anchor().y(),saved.anchor().z(),saved.dimension());
        BlockPos center = block(position);
        // 同一平台可放多台命名机器，观察时沿用目标名称选择档案，避免把压机图纸交给装配机。
        if (saved == null) saved = ClientMachineCatalog.blueprint(player,goal.target().label(),center).orElse(null);
        String label = optionalString(p, "label", 160);
        if (label == null) label = saved != null ? saved.label() : goal.target().label();
        if (label == null || label.isBlank()) throw bad("Give the machine a short label so subsequent analysis and operation refer to the same place");
        int radius = integer(p, "radius", 4, 0, 8);
        String mode = optionalString(p,"mode",16); if (mode == null) mode = "full";
        // 未指定半径时按登记足迹扩展局部组件扫描，最多八格；地图可读完整档案范围，工艺契约仍只匹配锚点。
        if (mode.equals("full")) radius = MachineInspectionBlueprintView.componentRadius(saved, center, radius, p.has("radius"));
        // full 从地图导出现状并附登记整机的差异，diff 只交付目标观察；两种模式都把未加载格保留为未知。
        var blueprintView = MachineInspectionBlueprintView.read(player,saved,center,radius,p.has("radius"),mode,
                integer(p,"offset",0,0,Integer.MAX_VALUE),integer(p,"limit",256,1,512));
        if (mode.equals("diff")) {
            // 已登记机器只查差异时直接交回整机目标的本页观察，不再扫描周边或排队补读组件库存。
            // 这里只提供差异事实，不生成菜单操作所需的区域快照；后续施工仍可复用原场地锚点。
            blueprintView.addProperty("label",label); blueprintView.addProperty("observation_only",true);
            runtime.remember(label,position);
            return new IntentAction.Report(TaskResult.ok("Recorded machine design compared with the current map; differences are observations only.",
                    Map.of("machine",blueprintView)),null);
        }
        if (!player.level().isLoaded(center) || !player.level().dimension().location().toString().equals(position.dimension())) {
            blueprintView.addProperty("label",label); blueprintView.addProperty("structure_complete",false);
            return new IntentAction.Report(TaskResult.ok("Machine location retained; unloaded map data remains unknown.",Map.of("machine",blueprintView)),null);
        }
        MachineSnapshots.Snapshot snapshot = MachineSnapshots.withInspectionView(MachineSnapshots.inspect(player, label, center, radius),blueprintView);
        runtime.remember(label, position);
        ClientMachineCatalog.inspected(player,label,center,radius);
        return new IntentAction.Native(new ServerMachineObservationTaskRecord(
                "machine-inspection-" + UUID.randomUUID(), player.level().getGameTime() + 1_200, snapshot,
                integer(p, "component_offset", 0, 0, 768), integer(p, "resource_offset", 0, 0, 4096)));
    }

    private static IntentAction design(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        // 组件关系图交给后台算布局，明确蓝图则直接检查；报告里的“审阅完成”不表示设计一定能建成。
        JsonObject p = goal.parameters();
        MachineSnapshots.Snapshot snapshot = goal.parameters().has("snapshot_id")
                ? boundSnapshot(goal, player, runtime) : null;
        var layout = compileLayout(p, player,snapshot==null?null:snapshot.center());
        if (layout == null) return IntentAction.Pending.INSTANCE;
        JsonObject report = p.has("design") ? MachineDesignReview.review(p.getAsJsonObject("design"),
                id -> registered(id, true), id -> registered(id, false)) : new JsonObject();
        report.addProperty("review_kind", p.has("design") ? "semantic_design" : "blueprint");
        if (p.has("blueprint_uri")) report.addProperty("blueprint_uri", p.get("blueprint_uri").getAsString());
        JsonObject outputSource = p.has("design") ? p.getAsJsonObject("design") : layout.blueprint();
        if (outputSource.has("expected_output") && (!p.has("design") || report.getAsJsonObject("validation").get("valid").getAsBoolean())) {
            report.add("recipe_evidence", MachineRecipeEvidence.inspect(player,
                    outputSource.get("expected_output").getAsString()));
            // 审阅只给目标材料的工艺入口；要选机器或继续拆原料时再读EMI，避免设计报告展开整棵配方树。
            report.addProperty("material_knowledge_uri", RecipeKnowledgeSource.uri(
                    ResourceLocation.parse(outputSource.get("expected_output").getAsString())));
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
            var result = ClientMachineWatches.cancel(player,UUID.fromString(requiredString(p,"job_id",36)));
            return new IntentAction.Report(TaskResult.ok("后台观察取消已请求；机器本身不会被关闭。",Map.of("monitor",result)),null);
        }
        if ("watch_production".equals(operation)) {
            if (!ServerAssistClient.supported("machine.watch")) throw bad("machine_watch_requires_server_support");
            MachineSnapshots.Snapshot snapshot = boundSnapshot(goal,player,runtime);
            var plan = new ProductionRunPlan(snapshot.center(),snapshot.dimension(),p.getAsJsonObject("production"));
            WatchLimits limits = watchLimits(p);
            var task = new MachineWatchTaskRecord(callId,player.level().getGameTime()+6000,
                    snapshot.label(),plan,limits.minimumProcessEvents(),limits.durationTicks(),limits.idleTicks());
            MachineSnapshots.consume(snapshot); return new IntentAction.Native(task);
        }
        if ("run_production".equals(operation)) {
            MachineProductionIntent.requireRuntime(p.getAsJsonObject("production"));
            MachineSnapshots.Snapshot snapshot = boundSnapshot(goal, player, runtime);
            var protections = new LinkedHashSet<>(goal.inheritedProtectionLabels());
            if (p.has("protected_labels")) p.getAsJsonArray("protected_labels").forEach(value -> protections.add(value.getAsString()));
            var task = MachineProductionIntent.createTask(callId, player.level().getGameTime() + 45L * 60 * 20,
                    player, snapshot.center(), snapshot.dimension(), p.getAsJsonObject("production"), null, List.copyOf(protections),
                    p.has("material_policy") ? SemanticMaterialSupplyCoordinator.MaterialPolicy.parse(p.get("material_policy").getAsString())
                            : SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY);
            if (task instanceof MachineProductionTaskRecord network)
                ClientMachineCatalog.registerPlan(player,snapshot.label(),network.plan);
            MachineSnapshots.consume(snapshot);
            return new IntentAction.Native(task);
        }
        if ("drive_vehicle".equals(operation)) {
            var destination=resolve(goal.target(),player,runtime);
            return new IntentAction.Native(new VehicleDriveTaskRecord(
                    callId,player.level().getGameTime()+15*60*20,UUID.fromString(requiredString(p,"structure_id",36)),
                    new Vec3(destination.x(),destination.y(),destination.z())));
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
        if ("set_control".equals(operation) && !p.has("snapshot_id")) return controlWithoutSnapshot(goal, player, runtime, callId, deadline);
        if ("station_process".equals(operation)) return stationProcess(goal, player, runtime, callId);
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

    /**
     * 不带观察编号拨拉杆：坐标目标只认那一格；地点或附近同名告示牌以它为中心取半径 4；nearest 以角色脚下为中心取半径 6。
     * 范围内必须恰好一根原版拉杆（或用 control_label 点名；点名格是告示牌时由执行器取其两格内唯一一根），
     * 提交时记下这一小范围的结构摘要，点击前复核没有变化。
     * 拉杆已是目标状态就直接完成，不会再拨一次；拨动后的机器是否运转、是否产出仍需另行观察。
     */
    private static IntentAction controlWithoutSnapshot(Goal goal, LocalPlayer player, IntentRuntime runtime,
                                                       String callId, long deadline) {
        JsonObject p = goal.parameters();
        String kind = goal.target().kind();
        BlockPos center = "nearest".equals(kind) ? player.blockPosition() : block(resolve(goal.target(), player, runtime));
        int radius = switch (kind) {
            case "coordinates" -> 0;
            case "nearest" -> 6;
            default -> 4;
        };
        if (!player.level().isLoaded(center)) throw bad("machine_control_region_unloaded: move closer to the lever first");
        String controlLabel = optionalString(p, "control_label", 160);
        BlockPos control = "coordinates".equals(kind) ? center : controlLabel == null ? null
                : block(resolve(new Goal.SemanticTarget("landmark", controlLabel, null, null), player, runtime));
        var request = new MachineControl.Request(player.level().dimension().location().toString(), center, radius,
                MachineSurvey.fingerprint(player, center, radius), bool(p, "powered", null), control);
        return new IntentAction.Native(MachineControl.task(callId, deadline, request));
    }

    /**
     * 现成置物台加工：坐标只认那一格；地点或附近同名告示牌周围半径 4 内必须恰好一台置物台；
     * nearest 取角色 6 格内最近的一台，距离相同不猜。放料、等待和收取都由原生右键完成并按实际观察回执。
     */
    private static IntentAction stationProcess(Goal goal, LocalPlayer player, IntentRuntime runtime, String callId) {
        JsonObject p = goal.parameters();
        String item = requiredString(p, "item_id", 256);
        if (!registered(item, false)) throw bad("unknown requested item: " + item);
        String kind = goal.target().kind();
        BlockPos center = "nearest".equals(kind) ? player.blockPosition() : block(resolve(goal.target(), player, runtime));
        BlockPos station = "coordinates".equals(kind) ? center : nearestDepot(player, center, "nearest".equals(kind) ? 6 : 4,
                !"nearest".equals(kind));
        if (!player.level().isLoaded(station) || !BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(station).getBlock())
                .toString().equals("create:depot"))
            throw bad("station_process_requires_create_depot: the target cell is not a loaded create:depot");
        int waitTicks = integer(p, "max_wait_seconds", 30, 1, 300) * 20;
        DepotStationProcessTaskRecord.install();
        return new IntentAction.Native(new DepotStationProcessTaskRecord(callId,
                player.level().getGameTime() + 2L * 60 * 20 + waitTicks, station,
                BuiltInRegistries.ITEM.get(ResourceLocation.parse(item)), waitTicks));
    }

    private static BlockPos nearestDepot(LocalPlayer player, BlockPos center, int radius, boolean requireUnique) {
        List<BlockPos> depots = new ArrayList<>();
        for (BlockPos at : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius)))
            if (player.level().isLoaded(at) && BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(at).getBlock())
                    .toString().equals("create:depot")) depots.add(at.immutable());
        if (depots.isEmpty()) throw bad("station_process_no_depot: no create:depot within " + radius + " blocks of the target");
        depots.sort(Comparator.comparingDouble(at -> at.distSqr(center)));
        if (depots.size() > 1 && (requireUnique || depots.get(0).distSqr(center) == depots.get(1).distSqr(center)))
            throw bad("station_process_ambiguous_depot: " + depots.size() + " depots are near the target; use target coordinates of the intended depot");
        return depots.getFirst();
    }

    private static IntentAction modify(Goal goal, LocalPlayer player, IntentRuntime runtime, UUID continuationToken) {
        // 应用蓝图与新建共用施工流程；连接动力则创建专门的 Create 任务，并保留原网络。
        JsonObject p = goal.parameters();
        if (!bool(p, "allow_modify", false)) throw bad("machine_modification_not_authorized: set allow_modify when the player's instructions authorize this change");
        if ("apply_blueprint".equals(p.get("operation").getAsString())) return build(goal, player, runtime);
        if ("connect_external_input".equals(p.get("operation").getAsString())) {
            MachineSnapshots.Snapshot snapshot = boundSnapshot(goal,player,runtime);
            var installation = ClientMachineCatalog.requireInstallation(player,snapshot.center(),snapshot.label());
            String inputId = requiredString(p,"input_id",64), sourceLabel = optionalString(p,"source_label",160);
            var input = installation.inputs().stream().filter(value -> value.id().equals(inputId)).findFirst()
                    .orElseThrow(() -> bad("machine_external_input_unknown: " + inputId));
            BlockPos source = sourceLabel == null ? null : block(resolve(new Goal.SemanticTarget("landmark",sourceLabel,null,null),player,runtime));
            var protections = new LinkedHashSet<>(goal.inheritedProtectionLabels());
            if (p.has("protected_labels")) p.getAsJsonArray("protected_labels").forEach(value -> protections.add(value.getAsString()));
            if (input.medium().equals("kinetic")) {
                var task = new EconomicKineticTaskRecord(
                        "machine-utility-"+UUID.randomUUID(),player.level().getGameTime()+15L*60*20,snapshot.dimension(),
                        sourceLabel,source,null,inputId,snapshot.center().offset(input.offset()),input.face(),input.blockId(),
                        input.minimumRpm()==null?0:input.minimumRpm(),integer(p,"source_radius",64,8,128),false,
                        SemanticMaterialSupplyCoordinator.MaterialPolicy.parse(optionalString(p,"material_policy",64)),List.copyOf(protections));
                MachineSnapshots.consume(snapshot);return new IntentAction.Native(task);
            }
            if(source==null)throw bad("source_label is required for this utility medium");
            var request = new UtilityConnectionTaskRecord.Request(
                    source,snapshot.center().offset(input.offset()),input.face(),input.blockId(),input.medium(),
                    input.minimumRpm() == null ? 0 : input.minimumRpm(),0,input.resource());
            var task = new UtilityConnectionTaskRecord(
                    "machine-utility-"+UUID.randomUUID(),player.level().getGameTime()+15L*60*20,snapshot.dimension(),sourceLabel,inputId,request,
                    SemanticMaterialSupplyCoordinator.MaterialPolicy.parse(optionalString(p,"material_policy",64)),List.copyOf(protections));
            MachineSnapshots.consume(snapshot); return new IntentAction.Native(task);
        }
        // 有内部续接编号时核对原接线前段；没有时由修改入口读取同一目标锚点，不要求模型另发一次勘测。
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
        // 先绑定真实施工锚点，再区分背包材料与已安装部件；不因完整蓝图包含自己的旧电机而拒绝续建。
        MachineSnapshots.Snapshot snapshot = boundSnapshot(goal, player, runtime, true);
        var layout = compileLayout(p, player,snapshot.center());
        if (layout == null) return IntentAction.Pending.INSTANCE;
        if (p.has("production") && !MachineUtilityInputs.parse(layout.blueprint()).isEmpty())
            throw bad("external_utility_connection_is_separate: build without production, connect_external_input, then run_production");
        if (layout.buildable()) {
            boolean modification = MODIFY.equals(goal.ability());
            // 建造授权覆盖蓝图点名格的地形和旧部件；保留选项仅在调用方主动收紧时生效，不新增逐次许可门槛。
            boolean replace = replacementEnabled(p);
            JsonObject design = p.getAsJsonObject("design");
            BlockPos anchor = design == null ? snapshot.center() : MachineConstructionPlan.floorAnchor(snapshot.center(), layout);
            // 明确蓝图的偏移从观察中心算；自动生成布局则先换算地板锚点，两类输入的定位规则不同。
            // 已明确授权的修改直接执行所声明的拆换；额外选项只用于主动收紧范围，不再要求模型重复打开两个许可。
            var plan = MachineConstructionPlan.compile(anchor, layout, replace, replace && bool(p, "replace_block_entities", true));
            // modify_machine 的补丁身份独立于 replace_existing；只加新箱子也不能把原来整台机器的档案覆盖掉。
            if (modification) plan.markModification();
            // apply_blueprint 沿用已授权的明确目标，当前状态与可达性由内部施工读取，拆旧轴不再转成重新选址。
            if (replace) plan.bindAutomaticModification(player.level());
            // 普通建造若已获准替换自己的旧部件，也把原生放置归属交给施工，免得自建轴被通用白名单挡住。
            else plan.bindOwnedReplacements(player);
            if (!ClientMachineCatalog.registerInstallation(player,snapshot.label(),plan))
                return IntentAction.Pending.INSTANCE;
            List<String> protectedLabels = p.has("protected_labels")
                    ? StreamSupport.stream(p.getAsJsonArray("protected_labels").spliterator(), false)
                        .map(JsonElement::getAsString).toList() : List.of();
            long deadline = player.level().getGameTime() + Math.max(45L * 60 * 20,
                    (long) (plan.blocks().size() + plan.parts().size()) * 100);
            var task = new MachineBuildTaskRecord("machine-" + UUID.randomUUID(), deadline, plan,
                    snapshot.dimension(), SemanticMaterialSupplyCoordinator.MaterialPolicy.parse(
                            optionalString(p, "material_policy", 64)), protectedLabels, snapshot.label());
            TaskRecord execution = task;
            // 网络生产保留旧执行器；原生过程通过同一工厂包装建造顺序，不把v2误交给v1端口网络解析。
            if (p.has("production")) execution = MachineProductionIntent.createTask(task.getToolCallId(), deadline,
                    player, anchor, snapshot.dimension(), p.getAsJsonObject("production"), task, protectedLabels, task.materialPolicy);
            if (execution instanceof MachineProductionTaskRecord production)
                ClientMachineCatalog.registerPlan(player,snapshot.label(),production.plan);
            // 施工继续使用原场地锚点；已经搭过的方块由执行器复用，不要求工地维持开工前的结构指纹。
            // 普通机器操作观察仍一次性消费；施工定位复用不代表可以重复提交菜单、库存或设备控制动作。
            if (!snapshot.report().has("construction_site")) MachineSnapshots.consume(snapshot);
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

    private static void validateConstructionOptions(JsonObject p, boolean modification) {
        // 参数只校验类型；不因重复许可字段的组合把已授权原生施工挡在入口。
        replacementEnabled(p); bool(p, "replace_block_entities", true);
        SemanticMaterialSupplyCoordinator.MaterialPolicy.parse(optionalString(p, "material_policy", 64));
    }

    private static boolean replacementEnabled(JsonObject p) {
        // 显式替换选项优先；未指定时只遵循作者明确声明的保留约束，其余蓝图范围默认可以拆换。
        JsonObject design = p.getAsJsonObject("design");
        boolean preserve = design != null && design.has("constraints")
                && design.getAsJsonObject("constraints").has("preserve_existing")
                && design.getAsJsonObject("constraints").get("preserve_existing").getAsBoolean();
        return bool(p, "replace_existing", !preserve);
    }

    private static SemanticMachineLayout.Result compileLayout(JsonObject p, LocalPlayer player,BlockPos siteCenter) {
        // 关系图需要计算具体布局；蓝图已经给出每格目标，只需按方块规则解析和检查。
        SemanticMachineLayout.Result result;
        if (p.has("design")) result = MachineLayoutJobs.poll(player, p.getAsJsonObject("design"));
        else {
            JsonObject blueprint = p.has("blueprint") ? p.getAsJsonObject("blueprint")
                    : PonderBlueprintStore.resolve(requiredString(p, "blueprint_uri", 2048));
            result = MachineConstructionPlan.reviewExplicit(MachineBlueprintDocument.compile(blueprint, MachineConstructionPlan.registry()));
        }
        if(result==null||!result.buildable())return result;
        BlockPos anchor=siteCenter==null?null:p.has("design")?MachineConstructionPlan.floorAnchor(siteCenter,result):siteCenter;
        return MachineSurvivalMaterials.requireSurvivalBlueprint(player,result,anchor);
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
        return boundSnapshot(goal, player, runtime, false);
    }

    static MachineSnapshots.Snapshot boundSnapshot(Goal goal, LocalPlayer player, IntentRuntime runtime, boolean construction) {
        if (MODIFY.equals(goal.ability())) {
            // 修改的目标与授权已经给定，由 Mod 自己读取锚点；旧观察只是参考，不再要求模型为每次拆换重发 inspect。
            Goal.WorldPosition target = resolve(goal.target(), player, runtime);
            return MachineSnapshots.constructionSite(player, goal.target().label(), block(target), 0);
        }
        // 先定位请求点名的机器，再校对观察编号；丢失缓存时只补读这个位置，不跟随角色当前脚位。
        String id = requiredString(goal.parameters(), "snapshot_id", 36);
        Goal.WorldPosition target = resolve(goal.target(), player, runtime);
        MachineSnapshots.Snapshot snapshot;
        try {
            snapshot = MachineSnapshots.requireForConstruction(player, id);
        } catch (IllegalArgumentException unavailable) {
            if (!unavailable.getMessage().startsWith("machine_snapshot_missing:")) throw unavailable;
            // 旧编号被淘汰或换会话后，已记住的工地仍能定位；返回当前观察让模型复核，再提交新绑定。
            var latest = construction ? MachineSnapshots.constructionSite(player, goal.target().label(), block(target), 4)
                    : MachineSnapshots.inspect(player, goal.target().label(), block(target), 4);
            throw new MachineSnapshotRejection("machine_snapshot_missing", id, latest);
        }
        if (!snapshot.center().equals(block(target)) || !snapshot.label().equalsIgnoreCase(goal.target().label())) {
            throw bad("machine_snapshot_target_mismatch: copy target and snapshot_id from the same observation; "
                    + "correct the mismatched request fields before obtaining another observation");
        }
        // 菜单、控制和生产都按当前结构重验；施工只借锚点，原料与运行条件由原生执行器实时读取。
        boolean production = OPERATE.equals(goal.ability())
                && "run_production".equals(optionalString(goal.parameters(),"operation",64));
        return construction ? snapshot
                : production ? MachineSnapshots.requireForProduction(player,id) : MachineSnapshots.requireFresh(player, id);
    }

    private static void requireMachineTarget(Goal goal) {
        // 大多数机器操作只能引用已记住的地点名，不接受再叠加坐标或关系描述。
        if (goal.target() == null || !Set.of("landmark", "area").contains(goal.target().kind())
                || goal.target().label() == null || goal.target().label().isBlank()
                || goal.target().position() != null || goal.target().relation() != null) {
            // 新建机器的目标由场地感知直接返回；已有设备的操作继续使用原本记住的地点。
            throw bad(BUILD.equals(goal.ability())
                    ? "build_machine requires target from the same perceive(view=construction_site) result as snapshot_id; reuse an existing observation if available"
                    : "This machine operation requires one remembered machine label as target");
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
                // 机器操作的地点同样可用附近唯一同名告示牌；观察编号仍按记住的标签核对同址。
                var place = runtime.targetPlace(target.label());
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
        // 默认值必须适用于短距离观察；显式指定值仍严格遵守范围限制。
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
