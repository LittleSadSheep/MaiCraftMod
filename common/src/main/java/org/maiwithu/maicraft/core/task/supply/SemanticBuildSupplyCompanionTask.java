// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.supply;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.build.BuildOrder;
import org.maiwithu.maicraft.core.task.build.BuildExcavationFrontier;
import org.maiwithu.maicraft.core.task.build.BuildExcavationCargo;
import org.maiwithu.maicraft.core.task.build.BuildExcavationSpoilSupply;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.build.BuildTraversabilityVerifier;
import org.maiwithu.maicraft.core.task.container.ContainerSupplySources;
import org.maiwithu.maicraft.core.tools.work.BuildTool;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 大建筑的供料父任务：先确定整份方案的材料，确认预览，再循环“取一批材料、建一批”，直到成品验收通过。 */
final class SemanticBuildSupplyCompanionTask
        extends AbstractCompanionTask<SemanticBuildSupplyTaskRecord> {
    private enum ChildKind { BUILD, BUILD_ACCESS }
    private record BatchNeed(Item item, int fetch, int consumableBeforeBlock) {}
    private record CargoConditions(int emptySlots, Map<ResourceLocation, Integer> spoilStacks,
            Map<BlockPos, Object> containers, Map<ResourceLocation, Long> observedStock) {}

    private final Map<Item, Integer> fullLedger = new LinkedHashMap<>();
    private Map<Item, Integer> initialRemaining = Map.of();
    private final List<Map<String, Object>> rounds = new ArrayList<>();
    private final List<Map<String, Object>> issues = new ArrayList<>();
    private Task activeChild;
    private TaskRecord activeRecord;
    private ChildKind activeKind;
    private int childSerial;
    private int buildRounds;
    private boolean batchVerified;
    private int remainingCellsBeforeBuild;
    private boolean prepared;
    private boolean cargoCheckPending = true;
    private CargoConditions deferredCargoConditions;
    private BlockPos cargoSearchOrigin;
    private String cleanupDeferredReason;
    private int cargoDeferrals;
    private long cargoRetryAt;
    private boolean cargoEffectsSeen, buildOutcomeUncertain, supplyOutcomeUncertain, spoilOutcomeUncertain;
    private String failureCode;
    private Map<String, Object> finalBuildData = Map.of();
    private BuildTraversabilityVerifier.Result traversabilityResult;
    private BuildTraversabilityVerifier.Verification traversabilityScan;
    private BuildTaskRecord activePlan;
    private SemanticBuildMaterialBinding.Proposal materialProposal;
    private final SemanticMaterialSupplyCoordinator supply =
            new SemanticMaterialSupplyCoordinator();
    private final BuildExcavationSpoilSupply spoilSupply = new BuildExcavationSpoilSupply();
    private final Map<ResourceLocation, ResourceLocation> selectedVariants =
            new LinkedHashMap<>();
    private final List<BlockPos> plannedMutationCells = new ArrayList<>();
    private final java.util.function.BiFunction<TaskRecord, BuildTaskRecord,
            org.maiwithu.maicraft.client.preview.PreviewSession.Decision> previewGate;

    SemanticBuildSupplyCompanionTask(
            LocalPlayer player, SemanticBuildSupplyTaskRecord record) {
        this(player, record, org.maiwithu.maicraft.core.task.build.BuildPreviewGate::await);
    }

    SemanticBuildSupplyCompanionTask(LocalPlayer player, SemanticBuildSupplyTaskRecord record,
            java.util.function.BiFunction<TaskRecord, BuildTaskRecord,
                    org.maiwithu.maicraft.client.preview.PreviewSession.Decision> previewGate) {
        super(player, record);
        activePlan = record.plan;
        this.previewGate = previewGate;
    }

    @Override
    protected void onStart() {
        var terrain = org.maiwithu.maicraft.core.task.build.BuildSiteConstraints.conflicts(player, activePlan.targets);
        if (!terrain.isEmpty()) {
            stopWith("build_terrain_conflict", "The design intersects terrain that cannot be excavated: "
                    + String.join("; ", terrain) + ". Revise basement depth, raise the building or choose another site.",
                    FailureType.NO_SUPPORT);
            return;
        }
        // 普通分批供料只接方块物品计划；摆设、箱内数据等组合效果要走专用流程。
        if (!activePlan.cellNeeds().isEmpty() || !activePlan.blockEntityData.isEmpty()
                || !activePlan.entities.isEmpty()) {
            stopWith("unsupported_semantic_material_effect",
                    "semantic material batching currently accepts ordinary BuildTool block-item plans only",
                    FailureType.UNSUPPORTED);
            return;
        }
        refreshLedgers();
        activePlan.targets.stream().map(BuildTaskRecord.Target::pos)
                .map(BlockPos::immutable).distinct().forEach(plannedMutationCells::add);
        plannedMutationCells.addAll(activePlan.materialSupplyProtection());
        if (allMatched()) {
            prepared = true;
            return;
        }
        try {
            materialProposal = SemanticBuildMaterialBinding.propose(
                    activePlan, r.broadenMaterialFamilies);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            stopWith("material_binding_invalid",
                    "construction stayed untouched because its semantic material families "
                            + "could not be represented safely: " + invalid.getMessage(),
                    FailureType.UNSUPPORTED);
            return;
        }
        if (materialProposal.empty()) {
            prepared = true;
        } else {
            advanceMaterialBinding();
        }
    }

    @Override
    protected TaskState onTick() {
        // 正在取料或施工就先推进那件事；不能看见目标方块恰好都存在，就跳过尚未结束的点击和支撑清理。
        if (supply.active()) return tickSupply();
        if (spoilSupply.active()) return tickSpoilSupply();
        if (activeChild != null) return tickChild();
        if (traversabilityScan != null) return finishMatched();
        if (!prepared) {
            advanceMaterialBinding();
            return failureCode == null ? TaskState.RUNNING : TaskState.FAILED;
        }

        var preview = previewGate.apply(r, activePlan);
        // 材料型号先定好给玩家看，但真正获取材料要等玩家确认，避免还没同意方案就出去采集。
        if (preview == org.maiwithu.maicraft.client.preview.PreviewSession.Decision.WAITING)
            return TaskState.RUNNING;
        if (preview == org.maiwithu.maicraft.client.preview.PreviewSession.Decision.CANCELLED)
            return TaskState.CANCELLED;

        // 新开或恢复项目、施工批次结束都检查旧土石；先出坑再存余料，真实存入确认后才取新建材。
        if (cargoCheckPending && prepareCargo()) return failureCode == null ? TaskState.RUNNING : TaskState.FAILED;
        if (allMatched() && !activePlan.hasTrackedScaffolds() && (buildRounds == 0 || batchVerified)) return finishMatched();

        boolean excavationNeeded = activePlan.targets.stream().anyMatch(target -> player.level().isLoaded(target.pos())
                && !player.level().getBlockState(target.pos()).isAir() && !constructionMatches(target));
        if (excavationNeeded && !activePlan.hasTrackedScaffolds()) {
            startBuild();
            return TaskState.RUNNING;
        }
        BatchNeed need = nextNeed();
        if (need == null) {
            startBuild();
            return TaskState.RUNNING;
        }
        if (need.fetch() > 0) {
            // 坑底、屋顶和图纸旁脚手架都先交给施工离场；未找到可靠出口时不能让仓库失败再递归去采原料。
            if (!prepareSupplyAccess()) startBatchSupply(need);
            return failureCode == null ? TaskState.RUNNING : TaskState.FAILED;
        }
        if (need.consumableBeforeBlock() > 0) {
            startBuild();
            return TaskState.RUNNING;
        }
        stopWith("inventory_capacity_blocked",
                "the next verified construction material cannot fit in the synchronized inventory; "
                        + "construction paused before another world change",
                FailureType.NO_SPACE);
        return TaskState.FAILED;
    }

    private TaskState tickChild() {
        // 施工小任务自己超时就停；它有真实进展而延长时间时，总供料任务也跟着延长，避免外层先超时。
        TaskState terminal;
        if (player.level().getGameTime() >= activeRecord.getDeadlineGameTime()) {
            activeChild.stop(player, Task.StopReason.REPLACED);
            terminal = TaskState.TIMEOUT;
        } else {
            terminal = runChild(activeChild);
            if (terminal == null) {
                // Child records own their liveness evidence. Carry any progress-based renewal
                // outward so a large healthy construction child is not cut off by
                // the coordinator's original estimate.
                r.extendDeadlineTo(activeRecord.getDeadlineGameTime());
                return TaskState.RUNNING;
            }
        }
        TaskResult result = activeChild.result(terminal);
        ChildKind kind = activeKind;
        // 每种行为单独保留未知结果；只有正常施工重新核验了整份方案，才能解除这一类世界变更未知。
        boolean buildVerified = kind == ChildKind.BUILD && batchCompleted(terminal, result)
                && !outcomeUnknown(result == null ? null : result.data()) && allMatched();
        buildOutcomeUncertain = outcomeUnknown(result == null ? null : result.data()) || buildOutcomeUncertain && !buildVerified;
        recordRound(kind, terminal, result);
        activeChild = null;
        activeRecord = null;
        activeKind = null;
        cargoCheckPending = true;

        if (kind == ChildKind.BUILD_ACCESS) {
            // 出口完成单独记账，并复查身体高度；它不能替代成品验收，也不能放宽仓库的普通导航权限。
            boolean ready = !buildOutcomeUncertain && terminal == TaskState.SUCCESS && result != null && result.success()
                    && result.data() != null && Boolean.TRUE.equals(result.data().get("supply_access_only"))
                    && Boolean.TRUE.equals(result.data().get("supply_access_ready"))
                    && BuildExcavationFrontier.supplyAccess(player, activePlan).status() == BuildExcavationFrontier.AccessStatus.READY;
            if (ready) return TaskState.RUNNING;
            stopFromChild("construction_supply_access_failed",
                    "could not reach the exterior ground before material supply"
                            + (result == null || result.message() == null ? "" : ": " + result.message()),
                    result, FailureType.NO_PATH);
            return TaskState.FAILED;
        }

        if (result != null && result.data() != null) finalBuildData = Map.copyOf(result.data());
        batchVerified = batchCompleted(terminal, result) && !buildOutcomeUncertain;
        if (batchVerified && allMatched() && !activePlan.hasTrackedScaffolds()) return TaskState.RUNNING;
        String childCode = result == null || result.data() == null
                ? null : String.valueOf(result.data().get("failure_code"));
        int remainingNow = remainingCellCount();
        boolean progress = remainingNow < remainingCellsBeforeBuild || result != null && result.data() != null
                && result.data().get("cleared") instanceof Number count && count.intValue() > 0;
        if (terminal == TaskState.FAILED && !buildOutcomeUncertain
                && ("material_exhausted".equals(childCode)
                        || "missing_materials".equals(childCode)) && progress) {
            // 确实建了一些、只是材料用完，才自动进入下一轮补料；其他失败停下来等判断，不盲目继续重建。
            return TaskState.RUNNING;
        }
        stopFromChild("construction_batch_failed",
                progress
                        ? "construction stopped after a non-material failure; no blind retry was attempted"
                        : "construction made no verified progress; no blind retry was attempted",
                result, FailureType.UNKNOWN);
        return TaskState.FAILED;
    }

    /**
     * Bind every planner family before preview using carried evidence only. Registry alternatives
     * are compatible shapes, not observed supply sources. With no carried evidence keep the
     * design's material requirement; all fetching waits for review and targets that frozen item.
     */
    static boolean batchCompleted(TaskState terminal, TaskResult result) {
        if (terminal != TaskState.SUCCESS || result == null || !result.success()) return false;
        if (result.data() != null && Boolean.TRUE.equals(result.data().get("supply_access_only"))) return false;
        Object remaining = result.data() == null ? null : result.data().get("remaining_scaffolds");
        return remaining == null || remaining instanceof java.util.Collection<?> cells && cells.isEmpty();
    }

    private void advanceMaterialBinding() {
        // 每种可替代材料只选一个实际型号，冻结整份方案；之后每批沿用相同材料，避免建到一半换样子。
        if (failureCode != null || prepared) return;
        try {
            for (var family : materialProposal.families()) {
                selectedVariants.put(family.groupId(), SemanticBuildMaterialBinding.select(family,
                        id -> inventoryCount(BuiltInRegistries.ITEM.get(id))));
            }
            activePlan = SemanticBuildMaterialBinding.bind(
                    r.plan, materialProposal, Map.copyOf(selectedVariants));
            // Save the concrete palette shown in preview before any supply or construction runs.
            activePlan.persistProject();
            refreshLedgers();
            prepared = true;
        } catch (IllegalArgumentException invalid) {
            stopWith("material_variant_selection_invalid",
                    "construction stayed untouched because semantic supply could not bind one "
                            + "reviewed concrete variant per material family: "
                            + invalid.getMessage(), FailureType.NO_MATERIAL);
        }
    }

    private void startBatchSupply(BatchNeed need) {
        // 取物任务用“最终背包要有多少”表达需求，因此把现有数量加上这一批还要取的数量。
        Item item = need.item();
        int requiredFinal = Math.min(SemanticAcquireTaskRecord.MAX_FINAL_COUNT,
                inventoryCount(item) + need.fetch());
        beginSupply(new SemanticMaterialSupplyCoordinator.Demand(
                List.of(itemId(item)), requiredFinal,
                "supply the next verified construction batch"));
    }

    private void beginSupply(SemanticMaterialSupplyCoordinator.Demand demand) {
        // 在仓库连续取齐下一批建材，之后由施工任务按已有支撑选择站位，不要求普通导航爬回旧墙顶。
        supply.begin(player, r.getToolCallId(), r.getDeadlineGameTime(), demand,
                r.materialPolicy, r.allowedSources, r.allowHarm, r.protectedLabels, List.of(),
                SemanticMaterialSupplyCoordinator.ReturnPolicy.CALLER_HANDOFF);
        r.extendDeadlineTo(supply.childDeadline());
    }

    private TaskState tickSupply() {
        // 为建筑取料时，建筑目标和专门保护区不能成为采矿来源，避免边建边把自己的建筑当材料挖掉。
        SemanticMaterialSupplyCoordinator.Tick tick = NavigationSafetyContext.withProtectedArea(
                plannedMutationCells, List.of(),
                () -> supply.tick(player, this::runChild));
        r.extendDeadlineTo(supply.childDeadline());
        if (tick.status() == SemanticMaterialSupplyCoordinator.Status.RUNNING) {
            return TaskState.RUNNING;
        }
        recordSupplyRound(tick);
        supplyOutcomeUncertain = outcomeUnknown(tick.receipt()) || supplyOutcomeUncertain
                && !(tick.status() == SemanticMaterialSupplyCoordinator.Status.SUPPLIED_REPLAN
                        && tick.receipt() != null && Boolean.TRUE.equals(tick.receipt().get("goal_satisfied")));
        if (tick.status() == SemanticMaterialSupplyCoordinator.Status.FAILED || supplyOutcomeUncertain) {
            stopWith("material_batch_supply_failed",
                    tick.message(), tick.failureType());
            return TaskState.FAILED;
        }
        return TaskState.RUNNING;
    }

    private void recordSupplyRound(SemanticMaterialSupplyCoordinator.Tick tick) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", "material_batch");
        value.put("terminal_state", tick.status().name().toLowerCase());
        value.put("remaining_cells", remainingCellCount());
        value.put("message", tick.message() == null ? "" : tick.message());
        if (tick.receipt() != null && !tick.receipt().isEmpty()) {
            value.put("supply", tick.receipt());
        }
        rounds.add(Map.copyOf(value));
    }

    private void startBuild() {
        startBuild(false);
    }

    private boolean prepareCargo() {
        // 只用背包策略不擅自开仓；其他建造策略沿用开挖期间已有的授权普通仓库存入流程。
        if (r.materialPolicy == SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY) {
            cargoCheckPending = false; return false;
        }
        Map<ResourceLocation, Integer> excess = BuildExcavationCargo.surplus(player, ledger(true));
        if (excess.isEmpty()) { cargoCheckPending = false; clearCargoDeferral(); return false; }
        if (deferredCargoConditions != null) {
            // 只在批次边界复查；一份建材的消耗不会触发重试，空位、整组余料或已观察仓库须有变化。
            boolean changed = cargoDeferrals < 3 && player.level().getGameTime() >= cargoRetryAt
                    && !deferredCargoConditions.equals(cargoConditions(excess));
            if (!changed) {
                cargoCheckPending = false;
                if (!canContinueWithCargo()) {
                    stopWith("inventory_capacity_blocked", "Deferred surplus still has no storage and the next construction batch has no safe inventory space.", FailureType.NO_SPACE);
                    return true;
                }
                return false;
            }
        }
        if (prepareSupplyAccess()) return true;
        cargoCheckPending = false;
        cargoSearchOrigin = player.blockPosition().immutable();
        cargoEffectsSeen = false;
        spoilSupply.begin(player, childId("excavation-spoil"), r.getDeadlineGameTime(), excess, r.protectedLabels, 48);
        return true;
    }

    private boolean prepareSupplyAccess() {
        // 同一离场证明同时服务于存废料和取建材；未知出口不冒充已到地面，也不授予普通仓库改地形权限。
        var access = BuildExcavationFrontier.supplyAccess(player, activePlan);
        if (access.status() == BuildExcavationFrontier.AccessStatus.READY) return false;
        if (access.status() == BuildExcavationFrontier.AccessStatus.BLOCKED)
            stopWith("construction_supply_exit_unavailable", "No verified exterior supply route: " + access.code(), FailureType.NO_PATH);
        else startBuild(true);
        return true;
    }

    private TaskState tickSpoilSupply() {
        // 所有物品通过看得见的容器界面逐箱存放，当前建筑和保护区不能为了找仓库被拆开。
        var tick = NavigationSafetyContext.withProtectedArea(plannedMutationCells, List.of(),
                () -> spoilSupply.tick(player, this::runChild));
        cargoEffectsSeen |= Boolean.TRUE.equals(tick.receipt().get("effects_started"))
                || tick.receipt().get("last_container_receipt") instanceof Map<?, ?> child && Boolean.TRUE.equals(child.get("effects_started"));
        spoilOutcomeUncertain = outcomeUnknown(tick.receipt()) || spoilOutcomeUncertain
                && tick.status() != BuildExcavationSpoilSupply.Status.DEPOSITED;
        r.extendDeadlineTo(spoilSupply.childDeadline());
        if (tick.status() == BuildExcavationSpoilSupply.Status.RUNNING) return TaskState.RUNNING;
        rounds.add(Map.of("kind", "excavation_spoil", "terminal_state", tick.status().name().toLowerCase(), "data", tick.receipt()));
        if (tick.status() == BuildExcavationSpoilSupply.Status.FAILED || spoilOutcomeUncertain) {
            if (!cargoEffectsSeen && storageUnavailableWithoutEffects(tick.receipt()) && cargoMenuSettled()) {
                if (!canContinueWithCargo()) {
                    stopWith("inventory_capacity_blocked", "Storage has no available capacity and the carried inventory cannot safely accept the next construction batch.", FailureType.NO_SPACE);
                    return TaskState.FAILED;
                }
                // 箱子不存在或全满，但没有动过物品且仍能施工时，先记住条件；不能因为想清包而卡死建造。
                cleanupDeferredReason = tick.receipt().get("failure_code").toString();
                deferredCargoConditions = cargoConditions(BuildExcavationCargo.surplus(player, ledger(true)));
                cargoDeferrals++; cargoRetryAt = player.level().getGameTime() + 30L * 20;
                cargoCheckPending = false;
                return TaskState.RUNNING;
            }
            clearCargoDeferral();
            stopWith("excavation_spoil_storage_failed", "Excavation surplus storage stopped: " + tick.receipt(), FailureType.NO_SPACE);
            return TaskState.FAILED;
        }
        clearCargoDeferral();
        cargoCheckPending = true;
        return TaskState.RUNNING;
    }

    /** 只接受明确没有搬动物品的容量失败；曾点击、存入过部分物品或回执不确定都不能走延期。 */
    static boolean storageUnavailableWithoutEffects(Map<String, Object> receipt) {
        Object code = receipt.get("failure_code");
        if (!("excavation_spoil_no_safe_loaded_container".equals(code) || "excavation_spoil_no_verified_storage_capacity".equals(code))
                || !Boolean.FALSE.equals(receipt.get("outcome_uncertain"))
                || Boolean.TRUE.equals(receipt.get("effects_started")) || !zeroCounts(receipt.get("confirmed_deposited"))) return false;
        Object last = receipt.get("last_container_receipt");
        if (last == null) return receipt.get("warehouse_attempts") instanceof Number attempts && attempts.doubleValue() == 0;
        if (!(last instanceof Map<?, ?> child) || !Boolean.FALSE.equals(child.get("outcome_uncertain"))
                || !Boolean.FALSE.equals(child.get("effects_started")) || !"deposit".equals(child.get("operation"))) return false;
        // 普通箱和专用 AE 存入使用各自的数量回执，两者都必须明确合计为零且没有点击副作用。
        return Boolean.TRUE.equals(child.get("bounded_storage_deposit")) && child.get("moved_count") instanceof Number moved
                && moved.doubleValue() == 0 && zeroCounts(child.get("moved_items"))
                || child.get("confirmed_deposited_total") instanceof Number total && total.doubleValue() == 0 && zeroCounts(child.get("deposited"));
    }

    private static boolean zeroCounts(Object value) {
        return value instanceof Map<?, ?> counts && counts.values().stream().allMatch(count -> count instanceof Number n && n.doubleValue() == 0);
    }

    private boolean cargoMenuSettled() {
        return !spoilSupply.mustSettleBeforeSatisfiedCancellation() && player.containerMenu == player.inventoryMenu
                && player.inventoryMenu.getCarried() != null && player.inventoryMenu.getCarried().isEmpty()
                && net.minecraft.client.Minecraft.getInstance().screen == null;
    }

    private boolean canContinueWithCargo() {
        if (allMatched() && !activePlan.hasTrackedScaffolds()) return true;
        if (emptySlots() < 4) return false;
        BatchNeed need = nextNeed();
        return need == null || need.fetch() > 0 || need.consumableBeforeBlock() > 0;
    }

    private int emptySlots() {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) if (player.getInventory().getItem(slot).isEmpty()) count++;
        return count;
    }

    private CargoConditions cargoConditions(Map<ResourceLocation, Integer> excess) {
        // 只观察已加载仓库的位置/身份和此前真实 GUI 缓存，绝不读取隐藏箱内物品；走路不会移动比较中心。
        return NavigationSafetyContext.withProtectedArea(plannedMutationCells, List.of(), () -> {
            Map<BlockPos, Object> containers = new LinkedHashMap<>();
            for (var candidate : ContainerSupplySources.candidates(player, cargoSearchOrigin, 48, List.copyOf(excess.keySet()), java.util.Set.of(), r.protectedLabels))
                for (BlockPos at : candidate.footprint()) containers.put(at, player.level().getBlockEntity(at));
            Map<ResourceLocation, Integer> stacks = new LinkedHashMap<>(); excess.forEach((item, count) -> stacks.put(item, (count + 63) / 64));
            return new CargoConditions(emptySlots(), Map.copyOf(stacks), Map.copyOf(containers),
                    ContainerSupplySources.observedCounts(player, cargoSearchOrigin, 48, r.protectedLabels));
        });
    }

    private void clearCargoDeferral() { deferredCargoConditions = null; cleanupDeferredReason = null; }

    private static boolean outcomeUnknown(Map<?, ?> data) {
        return data != null && (Boolean.TRUE.equals(data.get("outcome_uncertain")) || Boolean.TRUE.equals(data.get("world_change_uncertain")));
    }

    private boolean unresolvedOutcome() {
        // 干净的仓库回执只证明存入已确认，不能把后来门、楼梯等放置产生的未知状态覆盖成 false。
        return buildOutcomeUncertain || outcomeUnknown(finalBuildData) || supplyOutcomeUncertain || spoilOutcomeUncertain
                || outcomeUnknown(spoilSupply.receipt());
    }

    private void startBuild(boolean accessOnly) {
        // 新建一份施工任务单，共享整份蓝图的保护、预览和脚手架记录；世界里已经正确的格子会跳过。
        if (!accessOnly) { buildRounds++; batchVerified = false; }
        long now = player.level().getGameTime();
        BuildTaskRecord source = activePlan;
        BuildTaskRecord batch = new BuildTaskRecord(
                childId(accessOnly ? "build-access" : "build"), now + BuildTool.timeoutTicksFor(source.targets.size(), true),
                source.targets, source.replaceMode, source.replaceExisting,
                true, true, source.blockEntityData, source.entities, source.replaceBlockEntities);
        batch.cellNeeds(source.cellNeeds());
        batch.droppedAtLoad(source.droppedAtLoad());
        batch.semanticFacts(source.semanticFacts());
        batch.traversabilityContract(source.traversabilityContract());
        source.copyExecutionContextTo(batch);
        batch.supplyAccessOnly(accessOnly);
        batch.toolSupply(new BuildTaskRecord.ToolSupply(r.materialPolicy, r.allowedSources, r.allowHarm, r.protectedLabels));
        batch.previewManaged(true);
        if (!accessOnly) remainingCellsBeforeBuild = remainingCellCount();
        startChild(accessOnly ? ChildKind.BUILD_ACCESS : ChildKind.BUILD, batch);
    }

    private void startChild(ChildKind kind, TaskRecord record) {
        activeKind = kind;
        activeRecord = record;
        activeChild = TaskFactory.create(player, record);
    }

    /** Find the first material that blocks the shared BuildOrder using fresh inventory facts. */
    private BatchNeed nextNeed() {
        // 按实际施工顺序假算现有材料能做多少，找到第一种会卡住的材料，再按剩余需求和背包容量决定取多少。
        Map<Item, Integer> simulated = inventoryCounts();
        List<BuildTaskRecord.Target> ordered = activePlan.targets.stream()
                .sorted(BuildOrder.BUILD_ORDER).toList();
        int consumable = 0;
        Map<Item, Integer> remaining = ledger(true);
        for (BuildTaskRecord.Target target : ordered) {
            if (constructionMatches(target)) continue;
            int cost = target.materialCount();
            if (cost <= 0) continue;
            int have = simulated.getOrDefault(target.item(), 0);
            if (have >= cost) {
                simulated.put(target.item(), have - cost);
                consumable += cost;
                continue;
            }
            consumable += have;
            int capacity = capacityFor(target.item());
            int fetch = Math.min(capacity,
                    Math.max(0, remaining.getOrDefault(target.item(), cost)
                            - inventoryCount(target.item())));
            return new BatchNeed(target.item(), fetch, consumable);
        }
        return null;
    }

    private Map<Item, Integer> ledger(boolean onlyOutstanding) {
        // 剩余材料账跳过施工时可复用的方块；重要属性仍由子任务收尾，不为调整状态另取替换材料。
        Map<Item, Integer> result = new LinkedHashMap<>();
        for (BuildTaskRecord.Target target : activePlan.targets) {
            if (onlyOutstanding && constructionMatches(target)) continue;
            int count = target.materialCount();
            if (count > 0) result.merge(target.item(), count, Integer::sum);
        }
        return result;
    }

    // 备料按施工阶段是否可复用判断；新规则的同种方块先不购买替换材料，属性差异由子施工任务最后处理。
    private boolean constructionMatches(BuildTaskRecord.Target target) {
        return player.level().isLoaded(target.pos())
                && target.constructionMatches(player.level().getBlockState(target.pos()));
    }

    private boolean matches(BuildTaskRecord.Target target) {
        return player.level().isLoaded(target.pos())
                && target.matches(player.level().getBlockState(target.pos()));
    }

    private boolean allMatched() {
        return activePlan.targets.stream().allMatch(this::matches);
    }

    private TaskState finishMatched() {
        // 方块都对后，若方案要求能进门上楼，就再分刻验证通行；完成后才保留供后续任务引用的位置。
        if (activePlan.traversabilityContract() == null) {
            retainVerifiedPosition();
            return TaskState.SUCCESS;
        }
        if (traversabilityScan == null) {
            traversabilityScan = BuildTraversabilityVerifier.begin(
                    player.clientLevel, activePlan.traversabilityContract());
        }
        traversabilityResult = traversabilityScan.tick();
        if (traversabilityResult == null) {
            r.extendDeadlineTo(r.getDeadlineGameTime() + 1);
            return TaskState.RUNNING;
        }
        if (traversabilityResult.valid()) {
            retainVerifiedPosition();
            return TaskState.SUCCESS;
        }
        stopWith(traversabilityResult.code(), traversabilityResult.message(), FailureType.NO_PATH);
        return TaskState.FAILED;
    }

    private void retainVerifiedPosition() {
        if (activePlan.targets.isEmpty()) return;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BuildTaskRecord.Target target : activePlan.targets) {
            BlockPos pos = target.pos();
            minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ()); maxX = Math.max(maxX, pos.getX());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        r.retainVerifiedPosition(new InternalPositionReceipt.Position(
                Math.floorDiv(minX + maxX, 2), minY,
                Math.floorDiv(minZ + maxZ, 2),
                player.level().dimension().location().toString()));
    }

    private int remainingCellCount() {
        return (int) activePlan.targets.stream().filter(target -> !matches(target)).count();
    }

    private void refreshLedgers() {
        fullLedger.clear();
        fullLedger.putAll(ledger(false));
        initialRemaining = Map.copyOf(ledger(true));
    }

    private Map<Item, Integer> inventoryCounts() {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (int slot = 0; slot <= 35; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private int inventoryCount(Item item) {
        int count = 0;
        for (int slot = 0; slot <= 35; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private int capacityFor(Item item) {
        ItemStack sample = new ItemStack(item);
        int capacity = 0, empty = 0;
        for (int slot = 0; slot <= 35; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            // 取建材不能立即塞回满包；留四个空格给挖掘掉落、工具和合成中间产物。
            if (stack.isEmpty()) { if (++empty > 4) capacity += sample.getMaxStackSize(); }
            else if (stack.is(item)) capacity += Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }
        return capacity;
    }

    private static ResourceLocation itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    private void recordRound(ChildKind kind, TaskState state, TaskResult result) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", kind.name().toLowerCase());
        value.put("terminal_state", state.name().toLowerCase());
        value.put("remaining_cells", remainingCellCount());
        if (result != null) {
            value.put("success", result.success());
            value.put("message", result.message() == null ? "" : result.message());
            if (result.data() != null && !result.data().isEmpty()) {
                value.put("data", result.data());
            }
        }
        rounds.add(Map.copyOf(value));
    }

    private void stopFromChild(
            String code, String message, TaskResult result, FailureType type) {
        // 当前只认 outcome_uncertain，并把说明追加进文字；其他不确定字段不会在这里自动转换或传到外层。
        if (result != null && result.data() != null
                && outcomeUnknown(result.data())) {
            message += "; the child outcome is uncertain and must be observed before retry";
        }
        stopWith(code, message, type);
    }

    private void stopWith(String code, String message, FailureType type) {
        failureCode = code;
        issues.add(Map.of("code", code, "summary", message));
        fail(message, type);
    }

    private String childId(String suffix) {
        return r.getToolCallId() + "-internal-" + suffix + '-' + (++childSerial);
    }

    @Override
    protected Map<String, Object> resultData() {
        // 汇总材料账和每批结果；必须全部匹配、通行通过、无遗留支撑且最后施工批次确认成功，才报告目标满足。
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("material_policy", r.materialPolicy.id());
        data.put("material_palette_bound_before_construction", prepared);
        data.put("material_family_count",
                materialProposal == null ? 0 : materialProposal.families().size());
        data.put("total_material_ledger", stringLedger(fullLedger));
        data.put("initial_remaining_material_ledger", stringLedger(initialRemaining));
        data.put("remaining_material_ledger", stringLedger(ledger(true)));
        data.put("remaining_cells", remainingCellCount());
        data.put("cleanup_deferred", cleanupDeferredReason != null);
        if (cleanupDeferredReason != null) {
            Map<String, Integer> remaining = new LinkedHashMap<>();
            BuildExcavationCargo.surplus(player, ledger(true)).forEach((item, count) -> remaining.put(item.toString(), count));
            data.put("cleanup_deferred_reason", cleanupDeferredReason); data.put("cleanup_remaining", Map.copyOf(remaining));
            data.put("cleanup_capacity_deferrals", cargoDeferrals);
        }
        if (!spoilSupply.receipt().get("proven_spoil").equals(Map.of())) {
            data.put("excavation_spoil", spoilSupply.receipt());
        }
        data.put("outcome_uncertain", unresolvedOutcome());
        data.put("world_change_uncertain", buildOutcomeUncertain || outcomeUnknown(finalBuildData));
        boolean traversalSatisfied = activePlan.traversabilityContract() == null
                || traversabilityResult != null && traversabilityResult.valid();
        boolean complete = allMatched() && traversalSatisfied && !activePlan.hasTrackedScaffolds()
                && failureCode == null && !unresolvedOutcome() && !cargoCheckPending && !spoilSupply.active() && (buildRounds == 0 || batchVerified);
        data.put("goal_satisfied", complete);
        data.put("batches", List.copyOf(rounds));
        if (!issues.isEmpty()) data.put("issues", List.copyOf(issues));
        if (failureCode != null) {
            data.put("failure_code", failureCode);
            data.put("requires_decision", true);
            data.put("recovery_options", List.of(
                    Map.of("id", "free_inventory_space", "risk", "none"),
                    Map.of("id", "inspect_material_supply", "risk", "none"),
                    Map.of("id", "change_material_policy", "risk", "design_change"),
                    Map.of("id", "stop", "risk", "none")));
            if ("build_terrain_conflict".equals(failureCode)) {
                data.put("mechanical_retry_allowed", false);
                data.put("recovery_options", List.of(
                        Map.of("id", "reduce_basement_depth", "risk", "design_change"),
                        Map.of("id", "raise_building", "risk", "design_change"),
                        Map.of("id", "choose_another_site", "risk", "design_change"),
                        Map.of("id", "stop", "risk", "none")));
            }
        }
        if (traversabilityResult != null) {
            data.put("traversability_verification", traversabilityResult.evidence());
        }
        if (complete) {
            data.put("verified_position", verifiedCenter());
            if (!activePlan.semanticFacts().isEmpty()) {
                data.put("aggregate_verification", Map.of(
                        "status", "verified",
                        "basis", activePlan.traversabilityContract() == null
                                ? "every contract-bearing target cell was re-read after construction"
                                : "every target cell plus all planner-required traversal endpoints were re-read",
                        "facts", activePlan.semanticFacts()));
            }
        }
        else if (!finalBuildData.isEmpty()) data.put("last_build_evidence", finalBuildData);
        return data;
    }

    private Map<String, Integer> stringLedger(Map<Item, Integer> ledger) {
        Map<String, Integer> result = new LinkedHashMap<>();
        ledger.forEach((item, count) -> result.put(itemId(item).toString(), count));
        return result;
    }

    private Map<String, Object> verifiedCenter() {
        InternalPositionReceipt.Position position = r.internalVerifiedPosition();
        if (position == null) return Map.of();
        return Map.of("x", position.x(), "y", position.y(),
                "z", position.z(), "dimension", position.dimension(),
                "kind", "verified_site_center");
    }

    @Override protected String successMessage() {
        return "semantic material families selected and supplied across " + buildRounds
                + " verified construction batch(es), and every requested build cell"
                + (activePlan.traversabilityContract() == null ? "" : " and required route")
                + " re-verified" + (cleanupDeferredReason == null ? "" : "; surplus storage cleanup deferred: " + cleanupDeferredReason);
    }

    @Override protected String timeoutMessage() {
        if (failureCode == null) failureCode = "semantic_build_supply_timeout";
        return "semantic build supply timed out; review the remaining ledger before retrying";
    }

    @Override public Map<String, Object> progress() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", name());
        data.put("phase", spoilSupply.active() ? "storing_excavation_spoil" : supply.active() ? "material_supply" : activeKind == ChildKind.BUILD_ACCESS ? "preparing_supply_access" : activeChild != null ? "building"
                : traversabilityScan != null ? "verifying" : !prepared ? "preparing_materials"
                : "awaiting_preview_or_batch");
        data.put("construction_batches_started", buildRounds);
        if (spoilSupply.active()) data.put("child", spoilSupply.receipt());
        else if (supply.active()) data.put("child", supply.progress());
        else if (activeChild != null) data.put("child", activeChild.progress());
        return Map.copyOf(data);
    }

    @Override protected void cleanup() {
        // 总任务结束时，取料与施工小任务也要停止，并释放整份方案的预览记录。
        org.maiwithu.maicraft.core.task.build.BuildPreviewGate.release(r);
        if (supply.active()) {
            // 取料协调器取消时没有公开最终回执；若还在获取阶段，不能用旧的成功存入回执推断它已收尾。
            supplyOutcomeUncertain |= "acquiring_material".equals(supply.progress().get("phase"));
            supply.cancel(player);
        }
        if (spoilSupply.active()) spoilSupply.cancel(player);
        spoilOutcomeUncertain |= outcomeUnknown(spoilSupply.receipt());
        if (activeChild != null) {
            activeChild.stop(player, Task.StopReason.REPLACED);
            TaskResult stopped = activeChild.result(TaskState.CANCELLED);
            buildOutcomeUncertain |= outcomeUnknown(stopped == null ? null : stopped.data());
            activeChild = null;
        }
        super.cleanup();
    }

    @Override public boolean mustSettleBeforeSatisfiedCancellation() {
        // 即使施工格已全部匹配，正在存土石的鼠标和容器回执仍须先收尾，不能半次搬运时宣告完成。
        return spoilSupply.mustSettleBeforeSatisfiedCancellation()
                || activeChild != null && activeChild.mustSettleBeforeSatisfiedCancellation();
    }
}
