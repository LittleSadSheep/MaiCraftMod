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
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.build.BuildTraversabilityVerifier;
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
    private enum ChildKind { BUILD }
    private record BatchNeed(Item item, int fetch, int consumableBeforeBlock) {}

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
    private String failureCode;
    private Map<String, Object> finalBuildData = Map.of();
    private BuildTraversabilityVerifier.Result traversabilityResult;
    private BuildTraversabilityVerifier.Verification traversabilityScan;
    private BuildTaskRecord activePlan;
    private SemanticBuildMaterialBinding.Proposal materialProposal;
    private final SemanticMaterialSupplyCoordinator supply =
            new SemanticMaterialSupplyCoordinator();
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
        if (activeChild != null) return tickChild();
        if (traversabilityScan != null) return finishMatched();
        // Let the first-person child finish its receipt checks and scaffold cleanup before the
        // outer coordinator performs aggregate route verification.
        if (allMatched() && !activePlan.hasTrackedScaffolds() && (buildRounds == 0 || batchVerified)) return finishMatched();
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

        BatchNeed need = nextNeed();
        if (need == null) {
            startBuild();
            return TaskState.RUNNING;
        }
        if (need.fetch() > 0) {
            startBatchSupply(need);
            return TaskState.RUNNING;
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
        recordRound(kind, terminal, result);
        activeChild = null;
        activeRecord = null;
        activeKind = null;

        if (result != null && result.data() != null) finalBuildData = Map.copyOf(result.data());
        batchVerified = batchCompleted(terminal, result);
        if (batchVerified && allMatched() && !activePlan.hasTrackedScaffolds()) return finishMatched();
        String childCode = result == null || result.data() == null
                ? null : String.valueOf(result.data().get("failure_code"));
        int remainingNow = remainingCellCount();
        boolean progress = remainingNow < remainingCellsBeforeBuild;
        if (terminal == TaskState.FAILED
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
        supply.begin(player, r.getToolCallId(), r.getDeadlineGameTime(), demand,
                r.materialPolicy, r.allowedSources, r.allowHarm, r.protectedLabels);
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
        if (tick.status() == SemanticMaterialSupplyCoordinator.Status.FAILED) {
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
        // 新建一份施工任务单，共享整份蓝图的保护、预览和脚手架记录；世界里已经正确的格子会跳过。
        buildRounds++;
        batchVerified = false;
        long now = player.level().getGameTime();
        BuildTaskRecord source = activePlan;
        BuildTaskRecord batch = new BuildTaskRecord(
                childId("build"), now + BuildTool.timeoutTicksFor(source.targets.size(), true),
                source.targets, source.replaceMode, source.replaceExisting,
                true, true, source.blockEntityData, source.entities, source.replaceBlockEntities);
        batch.cellNeeds(source.cellNeeds());
        batch.droppedAtLoad(source.droppedAtLoad());
        batch.semanticFacts(source.semanticFacts());
        batch.traversabilityContract(source.traversabilityContract());
        source.copyExecutionContextTo(batch);
        batch.previewManaged(true);
        remainingCellsBeforeBuild = remainingCellCount();
        startChild(ChildKind.BUILD, batch);
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
            if (matches(target)) continue;
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
        // 完整账算全部目标，剩余账跳过当前已匹配的格子；清空和自动生成的另一半不重复算材料。
        Map<Item, Integer> result = new LinkedHashMap<>();
        for (BuildTaskRecord.Target target : activePlan.targets) {
            if (onlyOutstanding && matches(target)) continue;
            int count = target.materialCount();
            if (count > 0) result.merge(target.item(), count, Integer::sum);
        }
        return result;
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
        int capacity = 0;
        for (int slot = 0; slot <= 35; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) capacity += sample.getMaxStackSize();
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
                && Boolean.TRUE.equals(result.data().get("outcome_uncertain"))) {
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
        boolean traversalSatisfied = activePlan.traversabilityContract() == null
                || traversabilityResult != null && traversabilityResult.valid();
        boolean complete = allMatched() && traversalSatisfied && !activePlan.hasTrackedScaffolds()
                && failureCode == null && (buildRounds == 0 || batchVerified);
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
                + " re-verified";
    }

    @Override protected String timeoutMessage() {
        if (failureCode == null) failureCode = "semantic_build_supply_timeout";
        return "semantic build supply timed out; review the remaining ledger before retrying";
    }

    @Override public Map<String, Object> progress() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", name());
        data.put("phase", supply.active() ? "material_supply" : activeChild != null ? "building"
                : traversabilityScan != null ? "verifying" : !prepared ? "preparing_materials"
                : "awaiting_preview_or_batch");
        data.put("construction_batches_started", buildRounds);
        if (supply.active()) data.put("child", supply.progress());
        else if (activeChild != null) data.put("child", activeChild.progress());
        return Map.copyOf(data);
    }

    @Override protected void cleanup() {
        // 总任务结束时，取料与施工小任务也要停止，并释放整份方案的预览记录。
        org.maiwithu.maicraft.core.task.build.BuildPreviewGate.release(r);
        if (supply.active()) supply.cancel(player);
        if (activeChild != null) {
            activeChild.stop(player, Task.StopReason.REPLACED);
            activeChild.result(TaskState.CANCELLED);
            activeChild = null;
        }
        super.cleanup();
    }
}
