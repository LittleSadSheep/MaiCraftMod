// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.client.preview.PreviewSession.Decision;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.inventory.InventoryWorkItems;
import org.maiwithu.maicraft.core.WorkProfile;
import org.maiwithu.maicraft.core.integration.machine.assembly.AePartTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.assembly.FluidPlacementTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.assembly.MachineCommissioning;
import org.maiwithu.maicraft.core.integration.machine.assembly.MachineInstallation;
import org.maiwithu.maicraft.core.integration.machine.assembly.MekanismConfigureTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.assembly.MachineContentsTaskRecord;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.build.BuildPreviewGate;
import org.maiwithu.maicraft.core.task.supply.SemanticBuildSupplyTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.client.preview.PreviewPart;
import org.maiwithu.maicraft.core.integration.machine.assembly.FluidPlacementRules;
import org.maiwithu.maicraft.core.integration.machine.assembly.MekanismFilterTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.catalog.ClientMachineCatalog;
import org.maiwithu.maicraft.core.integration.machine.utility.MachineUtilityInputs;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.build.MachineSealingTaskRecord;
import org.maiwithu.maicraft.core.task.inventory.CreativeTakeItemsTaskRecord;
import org.maiwithu.maicraft.core.blueprint.BuildProjectStore;

/**
 * 整套机器装配的流程入口：勘测声明格、回收待移除流体，再施工方块、原生带段、附件与部件，封洞后倒流体。
 * 编译计划要求的初始物品、过滤及配置随后执行；子动作结清后保存整机并比较最终目标。
 * 原生动作失败保留已完成效果；动作结束后的结构或形成差异只作观察，不能据此宣称生产成功或让模型盲目重建。
 */
final class MachineBuildTask extends AbstractCompanionTask<MachineBuildTaskRecord> {
    private enum Phase { SURVEY, REMOVE_FLUIDS, BLOCKS, INSTALLATIONS, ATTACHMENTS, PARTS, SEAL, FLUIDS, CONTENTS, FILTERS, CONFIGURE, VERIFY, COMMISSION, BLUEPRINT_DIFF, DONE }
    private final Level world;
    private final Map<BlockPos, BlockState> preview;
    private final JsonArray configurations;
    private final List<PreviewPart> previewParts;
    private final List<BlockPos> plannedPositions;
    private final MachineBuildSurvey survey;
    private final Set<BlockPos> fluidPositions;
    private final JsonArray requirements;
    private final JsonArray initialContents;
    private final JsonArray filters;
    private final MachineBuildCompletion completion;
    private final JsonArray commissioning = new JsonArray();
    private final SemanticMaterialSupplyCoordinator supply = new SemanticMaterialSupplyCoordinator();
    private Phase phase = Phase.SURVEY;
    private Task child;
    private TaskRecord childRecord;
    private int partIndex, configIndex, verifyIndex, evidenceIndex, serial;
    private int verifyConfigIndex, requirementIndex;
    private int contentsIndex;
    private int filterIndex;
    private int fluidIndex;
    private String observationCode, observationDetail;
    private int removalIndex, confirmedSourceRemovals;
    private int installationIndex, verifyInstallationIndex, verifyProcessingIndex;
    private int attachmentIndex;
    private boolean assemblyVerified;
    private long commissioningDeadline;
    private boolean acquiringItem;
    private boolean blocksStarted;
    private boolean sealingStarted;
    private Map<String, Object> lastChild = Map.of();
    private Map<String, Object> failedSupply = Map.of();
    private Map<String, Object> failedNavigation = Map.of();
    private String failureCode;
    private JsonObject recordedMachine;
    private MachineBlueprintComparison comparison;
    private JsonObject comparisonIssue;
    private boolean machineLimitChecked;

    MachineBuildTask(LocalPlayer player, MachineBuildTaskRecord record) {
        super(player, record); world = player.level(); preview = record.plan.preview();
        plannedPositions = record.plan.positions();
        survey = new MachineBuildSurvey(record.plan);
        // 冻结待倒桶的目标位置，施工与补料继续识别这些源格，不把它们当成待挖空气。
        fluidPositions = record.plan.fluidTargets().stream().map(BuildTaskRecord.Target::pos)
                .collect(Collectors.toUnmodifiableSet());
        previewParts = record.plan.parts().stream()
                .map(part -> new PreviewPart(part.position(), part.spec().itemId(),
                        part.spec().side() == null ? "center" : part.spec().side().getSerializedName())).toList();
        JsonObject report = record.plan.report();
        completion = new MachineBuildCompletion(report.has("explicit_blueprint") && report.get("explicit_blueprint").getAsBoolean());
        configurations = report.has("configurations") ? report.getAsJsonArray("configurations") : new JsonArray();
        requirements = report.has("commissioning_requirements") ? report.getAsJsonArray("commissioning_requirements") : new JsonArray();
        initialContents = report.has("initial_contents") ? report.getAsJsonArray("initial_contents") : new JsonArray();
        filters = report.has("filters") ? report.getAsJsonArray("filters") : new JsonArray();
    }

    @Override protected TaskState onTick() {
        // 机器内部取料或清包时沿用整份装配和生产投入清单；作用域只维持本刻，不泄漏到下一项工作。
        return InventoryWorkItems.within(r.plan.workItems(), this::tickMachine);
    }

    private TaskState tickMachine() {
        if (player.level() != world) return failure("machine_world_changed", "The reviewed world changed.");
        // 开工前将本补丁与整机及此前失败施工累计检查；纯拆除不会因历史超额而再次被挡住。
        if (!machineLimitChecked) {
            var scope = MachineChainConveyorLimit.bind(player, r.getToolCallId(), r.label, r.plan.anchor(), true);
            var limit = MachineChainConveyorLimit.check(player, scope, MachineChainConveyorLimit.changes(r.plan));
            if (!limit.allowed()) return failure("machine_chain_conveyor_limit", limit.facts().toString());
            machineLimitChecked = true;
        }
        Decision decision = BuildPreviewGate.await(r, r.describe(), preview, previewParts);
        if (decision == Decision.WAITING) return TaskState.RUNNING;
        if (decision == Decision.CANCELLED) return TaskState.CANCELLED;
        // 有缺料任务时先把它推进完，取材期间保护机器计划格，避免为了材料拆掉当前机器。
        if (supply.active()) {
            var tick = NavigationSafetyContext.withProtectedArea(
                    plannedPositions, List.of(), () -> supply.tick(player, this::runChild));
            r.extendDeadlineTo(supply.childDeadline());
            if (tick.status() == SemanticMaterialSupplyCoordinator.Status.FAILED) {
                failedSupply = tick.receipt();
                return failure("machine_material_supply_failed", tick.message());
            }
            return TaskState.RUNNING;
        }
        if (child != null) return tickChild();
        return switch (phase) {
            case SURVEY -> surveyParts();
            case REMOVE_FLUIDS -> removeFluids();
            case BLOCKS -> buildBlocks();
            case INSTALLATIONS -> installNative();
            case ATTACHMENTS -> installAttachments();
            case PARTS -> installPart();
            case SEAL -> seal();
            case FLUIDS -> fillFluid();
            case CONTENTS -> contents();
            case FILTERS -> filters();
            case CONFIGURE -> configure();
            case VERIFY -> verify();
            case COMMISSION -> commission();
            case BLUEPRINT_DIFF -> compareCompletedMachine();
            case DONE -> TaskState.SUCCESS;
        };
    }

    /** 面板行动行的一句话汇报；阶段来自内部施工 Phase，零件进度来自 previewParts 与已装游标。 */
    @Override public String describeCurrentAction() {
        if (supply.active()) return "正在补齐机器建材";
        if (child != null) return child.describeCurrentAction();
        return switch (phase) {
            case SURVEY -> "正在勘察机器工地";
            case REMOVE_FLUIDS -> "正在清除工地流体";
            case BLOCKS -> "正在建造机器方块 " + preview.size() + " 格";
            case INSTALLATIONS -> "正在安装原生部件";
            case ATTACHMENTS -> "正在安装附件";
            case PARTS -> "正在安装机器零件 " + Math.min(partIndex + 1, previewParts.size()) + "/" + previewParts.size();
            case SEAL -> "正在封合机器结构";
            case FLUIDS -> "正在灌注机器流体";
            case CONTENTS -> "正在放入机器初始内容物";
            case FILTERS -> "正在配置机器过滤";
            case CONFIGURE -> "正在调整机器配置";
            case VERIFY -> "正在核验机器装配";
            case COMMISSION -> "正在试运行机器";
            case BLUEPRINT_DIFF -> "正在对比蓝图与成品";
            case DONE -> "正在收尾机器建造";
        };
    }

    private TaskState surveyParts() {
        var progress = survey.tick(world);
        if (progress.failure() != null) {
            // 已观测的占用与保护阻塞有确定类别；原生接口不可用等没有现场证据的异常仍按原失败路径报告。
            if (survey.failureEvidence().isEmpty()) return failure("machine_site_blocked", progress.failure() + " Construction has not started.");
            failureCode = "machine_site_blocked";
            fail(progress.failure() + " Construction has not started.", FailureType.TERRAIN_BLOCKED); return TaskState.FAILED;
        }
        if (progress.needsLoad() != null) return load(progress.needsLoad());
        if (progress.complete()) phase = r.plan.replaceExisting() ? Phase.REMOVE_FLUIDS : Phase.BLOCKS;
        return TaskState.RUNNING;
    }

    private TaskState removeFluids() {
        // 明确空气目标先收可取的源格 -> 做固体拆换和导流坑 -> 最后观察流体变化，不把等待退水设为开工条件。
        int budget = 128;
        while (removalIndex < r.plan.blocks().size() && budget-- > 0) {
            var target = r.plan.blocks().get(removalIndex);
            if (!target.desiredState().isAir()) { removalIndex++; continue; }
            if (!world.isLoaded(target.pos())) return load(target.pos());
            var state = world.getBlockState(target.pos());
            if (!(state.getBlock() instanceof LiquidBlock) || !state.getFluidState().isSource()) { removalIndex++; continue; }
            if (NavigationSafetyContext.protectsMutation(target.pos())) return failure("machine_fluid_removal_protected", "Declared source removal intersects a protected area.");
            if (!ensureItem(BuiltInRegistries.ITEM.getKey(Items.BUCKET))) return TaskState.RUNNING;
            start(FluidPlacementTaskRecord.removeSource(id(),deadline(),target.pos(),state,Set.copyOf(plannedPositions)));
            return TaskState.RUNNING;
        }
        if (removalIndex < r.plan.blocks().size()) return TaskState.RUNNING;
        phase = Phase.BLOCKS;
        return TaskState.RUNNING;
    }

    // 普通方块只启动一轮子任务，之后继续装部件；生存模式先经过供料，创造模式直接建。
    private TaskState buildBlocks() {
        Set<BlockPos> completedInstallations = new HashSet<>(survey.completedInstallations());
        // 流水不能靠挖掘变空气；它的最终状态留给后续diff，不能因此挡住同一蓝图里能执行的挖坑和放块。
        for (var target : r.plan.blocks()) if (target.desiredState().isAir() && world.isLoaded(target.pos())
                && world.getBlockState(target.pos()).getBlock() instanceof LiquidBlock) completedInstallations.add(target.pos());
        if (blocksStarted || r.plan.blocks().stream().allMatch(target -> MachineConstructionPlan.isFluid(target)
                || completedInstallations.contains(target.pos())) && survey.partClears().isEmpty()) { phase = Phase.INSTALLATIONS; return TaskState.RUNNING; }
        blocksStarted = true;
        boolean consume = !WorkProfile.of(player).freeMaterials();
        var plan = r.plan.blockTask(id(), r.getDeadlineGameTime(), consume, survey.partClears(), survey.openings(), completedInstallations);
        // 同一机器失败后再执行时接回原生确认的垫块；仍由现场核对决定已建格是否可复用。
        try { BuildProjectStore.available().ifPresent(store -> store.bindMachineStage(plan, world, player.getUUID())); }
        catch (RuntimeException unavailable) { return failure("machine_scaffold_recovery_failed", unavailable.getMessage()); }
        // 这份附加检查只确认世界对象和加载状态，没有保存并逐次比较场地旧方块；它不能代替施工器的替换许可。
        plan.executionGuards(r.plan.constructionAccess(plan), actor -> actor.level() == world,
                (actor, pos) -> actor.level() == world && actor.level().isLoaded(pos)
                        && (!fluidPositions.contains(pos) || actor.level().getFluidState(pos).isEmpty()), (actor, pos) -> {});
        if (consume) start(new SemanticBuildSupplyTaskRecord(id(), r.getDeadlineGameTime(), plan,
                r.materialPolicy, List.of(), false, r.protectedLabels, false));
        else start(plan);
        return TaskState.RUNNING;
    }

    private TaskState installNative() {
        // 方块准备完成后按作者顺序执行原生连接；已存在的整条结构不取材料、不重发点击。
        if (installationIndex >= r.plan.installations().size()) { phase = Phase.ATTACHMENTS; return TaskState.RUNNING; }
        var installation = r.plan.installations().get(installationIndex);
        if (installation.matches(world)) { installationIndex++; return TaskState.RUNNING; }
        for (var material : installation.materials(world).entrySet()) if (!ensureItem(material.getKey(), material.getValue())) return TaskState.RUNNING;
        start(installation.task(id(), deadline(), plannedPositions, r.protectedLabels)); return TaskState.RUNNING;
    }

    // 部件已在正确位置就跳过，否则先确保有物品，再交给 AE2 的原生安装任务。
    private TaskState installPart() {
        if (partIndex >= r.plan.parts().size()) { phase = Phase.SEAL; return TaskState.RUNNING; }
        var part = r.plan.parts().get(partIndex);
        if (!world.isLoaded(part.position())) return load(part.position());
        if (MachineInstallation.matches(world, part.position(), part.spec())) { partIndex++; return TaskState.RUNNING; }
        ResourceLocation item = BuiltInRegistries.ITEM.getKey(part.spec().item());
        if (ensureItem(item)) start(new AePartTaskRecord(id(), deadline(), part.position(), item.toString(), part.spec().side()));
        return TaskState.RUNNING;
    }
    private TaskState installAttachments() {
        if (attachmentIndex >= r.plan.attachmentLayers().size()) { phase = Phase.PARTS; return TaskState.RUNNING; }
        // 先确认本层全部支承已经存在，再让角色用原生物品安装；下层回执成功后才能推进上一层附件。
        for (var target : r.plan.attachmentLayers().get(attachmentIndex)) {
            for (BlockPos support : r.plan.placementDependencies().get(target.pos())) if (!world.isLoaded(support)) return load(support);
            try { r.plan.validatePlacementDependency(world, target.pos()); }
            catch (RuntimeException invalid) { return failure("native_placement_dependency_changed", invalid.getMessage()); }
        }
        boolean consume = !WorkProfile.of(player).freeMaterials();
        var task = r.plan.attachmentTask(attachmentIndex, id(), deadline(), consume);
        // 漏斗等后置附件也保留自己的支撑账，重试这一层不收编其他机器或其他玩家的方块。
        try { BuildProjectStore.available().ifPresent(store -> store.bindMachineStage(task, world, player.getUUID())); }
        catch (RuntimeException unavailable) { return failure("machine_scaffold_recovery_failed", unavailable.getMessage()); }
        task.executionGuards(List.of(), actor -> actor.level() == world, (actor, at) -> actor.level() == world && world.isLoaded(at), (actor, at) -> {});
        if (consume) start(new SemanticBuildSupplyTaskRecord(id(), r.getDeadlineGameTime(), task, r.materialPolicy, List.of(), false, r.protectedLabels, false));
        else start(task);
        return TaskState.RUNNING;
    }

    // 临时出入口在角色走到外面后封闭，随后直接逐格倒桶，不再整批预判流体是否会流出源格。
    private TaskState seal() {
        if (sealingStarted || r.plan.seals().isEmpty()) { phase = Phase.FLUIDS; return TaskState.RUNNING; }
        sealingStarted = true;
        start(new MachineSealingTaskRecord(id(), r.getDeadlineGameTime(),
                r.plan.seals(), r.materialPolicy, r.protectedLabels, plannedPositions));
        return TaskState.RUNNING;
    }

    private TaskState fillFluid() {
        if (fluidIndex >= r.plan.fluidTargets().size()) { phase = Phase.CONTENTS; return TaskState.RUNNING; }
        var target = r.plan.fluidTargets().get(fluidIndex);
        if (!world.isLoaded(target.pos())) return load(target.pos());
        // 其他源格可能已由原版补成源流体；逐格复读，符合目标就跳过，不能为计数好看再次倒桶。
        if (FluidPlacementRules.matches(world.getBlockState(target.pos()), target.desiredState())) {
            fluidIndex++; return TaskState.RUNNING;
        }
        // 有真实满桶就交给原生放置任务，按落点和桶账判断结果，不预先要求相邻流水格也声明成源格。
        if (ensureItem(BuiltInRegistries.ITEM.getKey(target.item()))) start(new FluidPlacementTaskRecord(id(), deadline(),
                target.pos(), target.desiredState(), Set.copyOf(plannedPositions)));
        return TaskState.RUNNING;
    }

    private TaskState contents() {
        if (contentsIndex >= initialContents.size()) { phase = Phase.FILTERS; return TaskState.RUNNING; }
        JsonObject content = initialContents.get(contentsIndex).getAsJsonObject();
        BlockPos at = MachineConstructionPlan.offset(r.plan.anchor(), content.get("offset"));
        if (!world.isLoaded(at)) return load(at);
        start(new MachineContentsTaskRecord(id(), deadline(), at, content.get("item_id").getAsString(), content.get("count").getAsInt()));
        return TaskState.RUNNING;
    }

    private TaskState configure() {
        if (configIndex >= configurations.size()) { phase = Phase.VERIFY; return TaskState.RUNNING; }
        JsonObject config = configurations.get(configIndex).getAsJsonObject();
        BlockPos position = MachineConstructionPlan.offset(r.plan.anchor(), config.get("offset"));
        if (!world.isLoaded(position)) return load(position);
        Direction face = Direction.byName(config.get("face").getAsString());
        if (face == null) return failure("machine_configuration_invalid", "Compiler produced an invalid port face.");
        if (MachineCompletionChecks.configurationMatches(world, r.plan.anchor(), config)) { configIndex++; return TaskState.RUNNING; }
        if (ensureItem(ResourceLocation.parse("mekanism:configurator"))) start(new MekanismConfigureTaskRecord(
                id(), deadline(), position, face, config.get("medium").getAsString(), config.get("mode").getAsString()));
        return TaskState.RUNNING;
    }

    private TaskState filters() {
        if (filterIndex >= filters.size()) { phase = Phase.CONFIGURE; return TaskState.RUNNING; }
        var filter = filters.get(filterIndex).getAsJsonObject();
        BlockPos at = MachineConstructionPlan.offset(r.plan.anchor(), filter.get("offset"));
        if (!world.isLoaded(at)) return load(at);
        start(new MekanismFilterTaskRecord(
                id(), deadline(), at, filter.get("item_id").getAsString()));
        return TaskState.RUNNING;
    }

    private boolean ensureItem(ResourceLocation id) {
        return ensureItem(id, 1);
    }
    // 先数普通背包；缺少时创造模式用原版取物任务，生存模式按声明的来源策略获取。
    private boolean ensureItem(ResourceLocation id, int count) {
        int have = player.getInventory().items.stream().filter(stack -> !stack.isEmpty()
                && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id)).mapToInt(ItemStack::getCount).sum();
        if (have >= count) return true;
        if (player.getAbilities().instabuild) {
            acquiringItem = true;
            start(new CreativeTakeItemsTaskRecord(id(), deadline(),
                    new ItemStack(BuiltInRegistries.ITEM.get(id)), count - have));
            return false;
        }
        supply.begin(player, r.getToolCallId(), r.getDeadlineGameTime(),
                new SemanticMaterialSupplyCoordinator.Demand(List.of(id), count, "machine assembly material"),
                r.materialPolicy, List.of(), false, r.protectedLabels);
        return false;
    }

    // 重新读取普通格、部件及已要求的接口配置；每次最多检查 128 项，不直接相信前一阶段说成功。
    private TaskState verify() {
        int budget = 128;
        int blocks = r.plan.blocks().size();
        while (verifyIndex < blocks + r.plan.parts().size() && budget-- > 0) {
            BlockPos position;
            if (verifyIndex < blocks) {
                var target = r.plan.blocks().get(verifyIndex); position = target.pos();
                if (!world.isLoaded(position)) return compareMismatch("machine_observation_incomplete", "A declared target is currently unloaded.");
                if (!r.plan.nativePositions().contains(position) && !target.matches(world.getBlockState(position)))
                    return compareMismatch("machine_geometry_changed", "A built target or maintenance clearance no longer matches the frozen layout.");
            } else {
                var part = r.plan.parts().get(verifyIndex - blocks); position = part.position();
                if (!world.isLoaded(position)) return compareMismatch("machine_observation_incomplete", "An installed part is currently unloaded.");
                if (!MachineInstallation.matches(world, position, part.spec()))
                    return compareMismatch("machine_part_changed", "An installed AE2 part no longer matches the frozen layout.");
            }
            verifyIndex++;
        }
        if (verifyIndex < blocks + r.plan.parts().size()) return TaskState.RUNNING;
        // 原生结构按最终带而不是准备轴验收；每刻核对一个安装／加工关系，避免一次遍历整个大工厂。
        if (verifyInstallationIndex < r.plan.installations().size()) {
            if (!r.plan.installations().get(verifyInstallationIndex++).matches(world)) return compareMismatch("native_installation_changed", "A native installation no longer matches the authored structure.");
            return TaskState.RUNNING;
        }
        if (verifyProcessingIndex < r.plan.processing().size()) {
            if (!r.plan.processing().get(verifyProcessingIndex++).matches(world)) return compareMismatch("processing_relationship_changed", "The processor, work surface or required clearance no longer matches the native contract.");
            return TaskState.RUNNING;
        }
        assemblyVerified = true;
        while (verifyConfigIndex < configurations.size() && budget-- > 0) {
            var config = configurations.get(verifyConfigIndex).getAsJsonObject();
            BlockPos at = MachineConstructionPlan.offset(r.plan.anchor(), config.get("offset"));
            if (!world.isLoaded(at)) return compareMismatch("machine_observation_incomplete", "A configured interface is currently unloaded.");
            if (!MachineCompletionChecks.configurationMatches(world, r.plan.anchor(), config))
                return compareMismatch("machine_configuration_changed", "A native interface mode changed before final acceptance.");
            verifyConfigIndex++;
        }
        if (verifyConfigIndex >= configurations.size()) {
            if (completion.acceptGeometry()) {
                return constructionCompleted();
            }
            phase = Phase.COMMISSION; commissioningDeadline = 0;
        }
        return TaskState.RUNNING;
    }

    // 收集部件观察，再逐条检查布局声明的形成要求；每条形成要求开始观察后最多等 200 刻。
    private TaskState commission() {
        int budget = 16;
        while (evidenceIndex < r.plan.components().size() && budget-- > 0) {
            BlockPos position = r.plan.components().get(evidenceIndex);
            if (!world.isLoaded(position)) return compareMismatch("machine_observation_incomplete", "A component is currently unloaded.");
            JsonObject evidence = MachineCommissioning.inspect(world, position);
            evidence.addProperty("component_index", evidenceIndex++); commissioning.add(evidence);
        }
        if (evidenceIndex < r.plan.components().size()) return TaskState.RUNNING;
        if (requirementIndex < requirements.size()) {
            JsonObject requirement = requirements.get(requirementIndex).getAsJsonObject();
            BlockPos missing = MachineCompletionChecks.regionToLoad(world, r.plan.anchor(), requirement);
            if (missing != null) return compareMismatch("machine_observation_incomplete", "A formation region is currently unloaded.");
            JsonObject evidence = MachineCompletionChecks.inspect(world, r.plan.anchor(), requirement);
            if (!MachineCompletionChecks.satisfied(requirement, evidence)) {
                commissioning.add(evidence);
                return compareMismatch("machine_commissioning_incomplete", "The native multiblock has not confirmed its expected formation and bounds.");
            }
            commissioning.add(evidence); requirementIndex++; commissioningDeadline = 0; return TaskState.RUNNING;
        }
        completion.acceptCommissioning();
        return constructionCompleted();
    }

    private void start(TaskRecord record) { childRecord = record; child = TaskFactory.create(player, record); }
    // 子任务结束后先取结果。容器缺料只有在动作已结清、自己打开的菜单已关闭时，才允许转去补料再回来。
    private TaskState tickChild() {
        TaskState state = player.level().getGameTime() >= childRecord.getDeadlineGameTime()
                ? TaskState.TIMEOUT : runChild(child);
        r.extendDeadlineTo(childRecord.getDeadlineGameTime());
        if (state == null) return TaskState.RUNNING;
        if (state == TaskState.TIMEOUT) child.stop(player, StopReason.REPLACED);
        TaskResult result = child.result(state);
        lastChild = result == null || result.data() == null ? Map.of() : result.data();
        child = null; childRecord = null;
        if (phase == Phase.CONTENTS && !acquiringItem && "machine_contents_material_shortage".equals(lastChild.get("failure_code"))
                && Boolean.TRUE.equals(lastChild.get("effects_settled")) && Boolean.TRUE.equals(lastChild.get("owned_menu_closed"))
                && lastChild.get("missing_count") instanceof Number missing && missing.intValue() > 0) {
            ensureItem(ResourceLocation.parse(initialContents.get(contentsIndex).getAsJsonObject().get("item_id").getAsString()), missing.intValue());
            return TaskState.RUNNING;
        }
        if (result == null || !result.success()) {
            // 机器包装保留原生阶段的失败类别；背包装不下时应先处理容量，而不是重新设计或重复补料。
            failureCode = "machine_assembly_stage_failed";
            fail(result == null ? "Native assembly did not produce a receipt." : result.message(), childFailure(lastChild));
            return TaskState.FAILED;
        }
        if (!acquiringItem && phase == Phase.PARTS) partIndex++;
        if (!acquiringItem && phase == Phase.INSTALLATIONS) installationIndex++;
        if (!acquiringItem && phase == Phase.ATTACHMENTS) attachmentIndex++;
        if (!acquiringItem && phase == Phase.CONFIGURE) configIndex++;
        if (!acquiringItem && phase == Phase.CONTENTS) contentsIndex++;
        if (!acquiringItem && phase == Phase.FILTERS) filterIndex++;
        if (!acquiringItem && phase == Phase.FLUIDS) fluidIndex++;
        if (!acquiringItem && phase == Phase.REMOVE_FLUIDS) {
            removalIndex++;
            if (Boolean.TRUE.equals(lastChild.get("native_effect_verified"))) confirmedSourceRemovals++;
        }
        acquiringItem = false;
        return TaskState.RUNNING;
    }

    private TaskState load(BlockPos target) {
        if (nav == null) nav = PlayerNav.toGoal(player, () -> NavGoal.column(target.getX(), target.getZ()), 1.0,
                () -> world.isLoaded(target), PlayerNav.ContextProvider.DEFAULT);
        var state = nav.tick();
        if (state == PlayerNav.Status.FAILED) return navigationFailure("machine_chunk_unreachable", "Cannot load the next construction region.", target);
        if (state == PlayerNav.Status.ARRIVED) stopNav();
        return TaskState.RUNNING;
    }
    private TaskState approach(BlockPos target) {
        if (nav == null) nav = PlayerNav.toGoal(player, () -> NavGoal.near(target, 3), 1.0,
                () -> player.blockPosition().distSqr(target) <= 9, PlayerNav.ContextProvider.DEFAULT);
        var state = nav.tick();
        if (state == PlayerNav.Status.FAILED) return navigationFailure("machine_commissioning_unreachable", "Cannot approach the multiblock for formation verification.", target);
        if (state == PlayerNav.Status.ARRIVED) stopNav();
        return TaskState.RUNNING;
    }
    // 新启动的单个装配动作先给三分钟；外层仍要传播或检查子任务期限。
    private long deadline() { return player.level().getGameTime() + 3 * 60 * 20; }
    private String id() { return r.getToolCallId() + "-assembly-" + (++serial); }
    private TaskState failure(String code, String message) { failureCode = code; fail(message, FailureType.UNKNOWN); return TaskState.FAILED; }

    // 先保存路线真正的结束原因，再停止并记账；自卫中断、地形阻挡与交通失败不能全部丢成UNKNOWN。
    private TaskState navigationFailure(String code, String message, BlockPos target) {
        FailureType type = nav.failType(); String reason = nav.failReason();
        failedNavigation = Map.of("target", Map.of("x", target.getX(), "y", target.getY(), "z", target.getZ()),
                "player_position", player.position().toString(), "failure_type", type.name().toLowerCase(Locale.ROOT),
                "reason", reason, "path_outcome", nav.outcomeSummary(), "transport", nav.transportDiagnostics());
        stopNav(); failureCode = code; fail(message + " Navigation: " + reason, type); return TaskState.FAILED;
    }

    /** 子任务的稳定失败词表向上传递；没有类别或协议外值仍保留未知，不能推断为普通缺料。 */
    static FailureType childFailure(Map<String, Object> data) {
        Object type = data.get("failure_type");
        if (type instanceof String text) {
            try { return FailureType.valueOf(text.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException unknown) { return FailureType.UNKNOWN; }
        }
        return FailureType.UNKNOWN;
    }
    // 结构完成后才自动留档；保存不接管身体，也不在搭建过程中持续扫描蓝图差异。
    private void rememberInstallation() { recordedMachine = ClientMachineCatalog.installationBuilt(player,r.plan,r.label); }
    private TaskState constructionCompleted() {
        r.verified(); rememberInstallation(); stopNav();
        return beginComparison();
    }
    private TaskState compareMismatch(String code, String message) {
        // 结构、形成和配置检查只产生观察；原生施工已经执行完，不因设计差异把任务改判失败。
        observationCode = code; observationDetail = message; stopNav();
        r.verified(); rememberInstallation();
        return beginComparison();
    }
    private TaskState comparisonFinished() {
        phase = Phase.DONE;
        return TaskState.SUCCESS;
    }
    private TaskState beginComparison() {
        // 修改完成后比较合并的整机声明，隔板拆除导致旁边岩浆凝固也要直接反馈，而不只回报本次一格补丁。
        MachineBlueprintDiff compared;
        try {
            // 档案尚未加载或留档失败时，不能拿局部补丁冒充整机；只公开观察缺失，不撤销已经完成的施工。
            var archived = recordedMachine == null || !recordedMachine.has("machine_id") ? null
                    : ClientMachineCatalog.blueprint(player, recordedMachine.get("machine_id").getAsString(), r.plan.anchor()).orElse(null);
            if (archived == null) {
                comparisonIssue = new JsonObject(); comparisonIssue.addProperty("comparison_complete",false);
                comparisonIssue.addProperty("reason","full_machine_blueprint_unavailable");
                return comparisonFinished();
            }
            compared = ClientMachineCatalog.comparison(archived);
            comparison = new MachineBlueprintComparison(compared,r.dimension);
        }
        catch (RuntimeException | LinkageError unavailable) {
            comparisonIssue = new JsonObject(); comparisonIssue.addProperty("comparison_complete",false);
            comparisonIssue.addProperty("reason","comparison_unavailable:"+unavailable.getClass().getSimpleName());
            return comparisonFinished();
        }
        phase = Phase.BLUEPRINT_DIFF;
        r.extendDeadlineTo(world.getGameTime() + 200L + (compared.size() + 127L) / 128);
        return TaskState.RUNNING;
    }
    private TaskState compareCompletedMachine() {
        try { if (!comparison.advance(world,128)) return TaskState.RUNNING; }
        catch (RuntimeException | LinkageError unavailable) {
            comparisonIssue = new JsonObject(); comparisonIssue.addProperty("comparison_complete",false);
            comparisonIssue.addProperty("reason","observation_unavailable:"+unavailable.getClass().getSimpleName());
        }
        return comparisonFinished();
    }

    // 结束时停止尚在运行的子任务、取消供料、释放预览，再清理公共导航状态。
    /** 机器总任务让出身体时继续向下通知实际施工/补料任务，保留同一子任务等待恢复。 */
    @Override public void stop(LocalPlayer companion, StopReason reason) {
        if (reason == StopReason.PREEMPTED) {
            if (child != null) child.stop(companion, reason);
            supply.pause(companion);
        }
        super.stop(companion, reason);
    }
    @Override protected void cleanup() {
        if (child != null) {
            child.stop(player, StopReason.REPLACED);
            // 外层取消或超时时仍须取回桶等子动作的最终账；不确定的原生副作用不能随子任务引用一起丢掉。
            TaskResult result = child.result(TaskState.CANCELLED);
            lastChild = result == null || result.data() == null ? Map.of() : result.data();
            child = null; childRecord = null;
        }
        supply.cancel(player); BuildPreviewGate.release(r); super.cleanup();
    }
    // 结构完工不替组件选择操作入口；无容器菜单的工作面也可正常加工，供料和实物产出仍须另外验证。
    // 收尾话术与 resultData 的实际附着物一致：对档占位（comparison_complete:false）不得声称"差异已附"（issue 024）。
    @Override protected String successMessage() {
        if (comparison != null) return "Native construction actions completed; observations and full-machine blueprint differences are attached. Actual machine operation and output remain unverified.";
        String reason = comparisonIssue == null ? "unknown" : String.valueOf(comparisonIssue.get("reason"));
        return "Native construction actions completed; observations are attached, but the full-machine blueprint comparison is unavailable ("
                + reason + "). Actual machine operation and output remain unverified.";
    }
    @Override public Map<String,Object> progress() {
        // 让上层建造/加工任务透出真正等待的原生阶段，避免站位或瞄准停滞只剩一个笼统的建造中状态。
        var data=new LinkedHashMap<String,Object>(); data.put("task",name()); data.put("phase",phase.name().toLowerCase(Locale.ROOT));
        if (comparison != null) { data.put("native_execution_complete",true); data.put("blueprint_diff",comparison.report()); }
        data.put("verified_source_fluid_targets",verifiedFluidTargets()); data.put("source_fluid_targets",r.plan.fluidTargets().size());
        data.put("processed_source_fluid_targets",fluidIndex);
        if(child!=null)data.put("native_stage",child.progress()); return Map.copyOf(data);
    }
    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>(completion.report());
        // 动作结束、静态匹配和功能成功三者独立；观察差异不转换成需要重试或等待决策的失败门控。
        data.put("native_execution_complete", phase == Phase.BLUEPRINT_DIFF || phase == Phase.DONE);
        if (observationCode != null) {
            data.put("observation_code",observationCode); data.put("observation_detail",observationDetail);
        }
        // 原生安装和加工净空也有独立勘测；开工前的阻塞证据不能只在普通方块子任务中才保留。
        if (!survey.failureEvidence().isEmpty()) data.put("clearance_report", survey.failureEvidence());
        if (!failedNavigation.isEmpty()) data.put("navigation_failure", failedNavigation);
        data.put("machine_layout", r.plan.report());
        if (recordedMachine != null) data.put("recorded_machine", recordedMachine.deepCopy());
        if (comparisonIssue != null) {
            data.put("blueprint_diff",comparisonIssue.deepCopy());
            // 无法完成整机比较时，局部施工检查即使通过，也不能对外宣称整机结构已经核实。
            data.put("machine_geometry_verified",false);
        }
        else if (comparison != null) {
            var observed = comparison.report(); data.put("blueprint_diff",observed);
            // 本次补丁已做完不等于整机仍完整；这里仅更新观察事实，仍不改变已完成动作的成功终态。
            data.put("machine_geometry_verified", observed.get("comparison_complete").getAsBoolean()
                    && observed.get("structure_matches_blueprint").getAsBoolean());
        }
        JsonArray plannedPorts = r.plan.report().getAsJsonArray("power_ports");
        if (plannedPorts != null) data.put("power_port_observations", MachinePowerPortObservations.observe(player.level() == world ? world : null, r.plan.anchor(), plannedPorts));
        data.put("native_installations_completed", installationIndex);
        data.put("attachment_layers_completed", attachmentIndex);
        data.put("processing_relationships_verified", assemblyVerified);
        if (!r.plan.utilityInputs().isEmpty()) {
            data.put("external_inputs",MachineUtilityInputs.json(r.plan.utilityInputs()));
            data.put("utility_connection_verified",false);
            data.put("next_phase","connect_external_input_then_run_production");
        }
        data.put("commissioning", commissioning); data.put("installed_parts", partIndex);
        // 附加部件与方块准备分别计数，失败时直接给出缺失格及原生站位结论，供模型继续原项目。
        data.put("installed_parts_scope", "addon parts only; excludes blocks, belts and attachment layers");
        if (!lastChild.isEmpty()) data.put("construction_progress", MachineBuildEvidence.summarize(phase.name().toLowerCase(Locale.ROOT), lastChild));
        data.put("configured_interfaces", configIndex); data.put("phase", phase.name().toLowerCase(Locale.ROOT));
        data.put("initialized_containers", contentsIndex);
        data.put("configured_output_filters", filterIndex);
        data.put("verified_source_fluid_targets", verifiedFluidTargets());
        data.put("processed_source_fluid_targets",fluidIndex);
        data.put("confirmed_source_fluid_removals",confirmedSourceRemovals);
        if (!lastChild.isEmpty()) data.put("last_native_stage", lastChild);
        // 方块供料和后置部件供料都带回同一类加工前置，模型可直接安排已知工序后再续建机器。
        if (!failedSupply.isEmpty()) MachineBuildEvidence.retainSupplyFailure(data, failedSupply);
        else if (lastChild.get("material_supply_failure") instanceof Map<?, ?> supplyFailure)
            MachineBuildEvidence.retainSupplyFailure(data, supplyFailure);
        // 常见恢复事实直接放在任务信封，注意流无需展开整份蓝图才能解释为什么停工。
        // 清障阻塞也透出坐标和整份蓝图的偏移建议，避免机器外层只报告笼统的施工失败。
        for (String key : List.of("inventory_capacity", "recovery_options", "clearance_report"))
            if (lastChild.containsKey(key)) data.put(key, lastChild.get(key));
        if (lastChild.containsKey("failure_code")) data.put("cause_code", lastChild.get("failure_code"));
        // 恢复策略读取任务信封顶层：桶可能已倒出时直接保留不确定与禁重试，不能只藏在原生阶段详情里。
        if (Boolean.TRUE.equals(lastChild.get("outcome_uncertain"))) {
            data.put("outcome_uncertain", true); data.put("mechanical_retry_allowed", false);
        } else if (Boolean.FALSE.equals(lastChild.get("mechanical_retry_allowed"))) data.put("mechanical_retry_allowed", false);
        if (failureCode != null) data.put("failure_code", failureCode);
        return data;
    }

    private long verifiedFluidTargets() {
        // 处理过几桶不等于留下几个源格；水岩浆反应后的验收数只读当前实际状态。
        return r.plan.fluidTargets().stream().filter(target -> world.isLoaded(target.pos())
                && FluidPlacementRules.matches(world.getBlockState(target.pos()), target.desiredState())).count();
    }
}
