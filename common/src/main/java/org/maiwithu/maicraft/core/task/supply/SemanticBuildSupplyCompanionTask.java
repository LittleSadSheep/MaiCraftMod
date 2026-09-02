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

/** Semantic material policy coordinator; concrete acquisition and placement stay Mod-owned. */
final class SemanticBuildSupplyCompanionTask
        extends AbstractCompanionTask<SemanticBuildSupplyTaskRecord> {
    private enum ChildKind { BUILD }
    private enum SupplyPurpose { SELECT_VARIANT, FETCH_BATCH }
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
    private int remainingCellsBeforeBuild;
    private boolean prepared;
    private String failureCode;
    private Map<String, Object> finalBuildData = Map.of();
    private BuildTraversabilityVerifier.Result traversabilityResult;
    private BuildTaskRecord activePlan;
    private SemanticBuildMaterialBinding.Proposal materialProposal;
    private final SemanticMaterialSupplyCoordinator supply =
            new SemanticMaterialSupplyCoordinator();
    private final Map<ResourceLocation, ResourceLocation> selectedVariants =
            new LinkedHashMap<>();
    private int materialFamilyIndex;
    private SemanticBuildMaterialBinding.Family selectingFamily;
    private SupplyPurpose supplyPurpose;
    private final List<BlockPos> plannedMutationCells = new ArrayList<>();

    SemanticBuildSupplyCompanionTask(
            LocalPlayer player, SemanticBuildSupplyTaskRecord record) {
        super(player, record);
        activePlan = record.plan;
    }

    @Override
    protected void onStart() {
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
        if (allMatched()) {
            prepared = true;
            if (verifyTraversability()) succeed();
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
        if (supply.active()) return tickSupply();
        if (activeChild != null) return tickChild();
        // Let the first-person child finish its receipt checks and scaffold cleanup before the
        // outer coordinator performs aggregate route verification.
        if (allMatched()) return finishMatched();
        if (!prepared) {
            advanceMaterialBinding();
            return failureCode == null ? TaskState.RUNNING : TaskState.FAILED;
        }

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
        if (allMatched()) return finishMatched();
        String childCode = result == null || result.data() == null
                ? null : String.valueOf(result.data().get("failure_code"));
        int remainingNow = remainingCellCount();
        boolean progress = remainingNow < remainingCellsBeforeBuild;
        if (terminal == TaskState.FAILED
                && ("material_exhausted".equals(childCode)
                        || "missing_materials".equals(childCode)) && progress) {
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
     * Bind every broad planner family before construction. A carried family member wins because
     * it is already the cheapest verified source. With no carried evidence, acquire exactly one
     * member of the whole acceptable family: the generic acquire planner then chooses storage
     * only when authorized, otherwise the cheapest live recipe/world branch (whose block child is
     * nearest-first). The selected concrete item is locked before any build cell changes.
     */
    private void advanceMaterialBinding() {
        while (failureCode == null && materialFamilyIndex < materialProposal.families().size()) {
            SemanticBuildMaterialBinding.Family family =
                    materialProposal.families().get(materialFamilyIndex);
            ResourceLocation carried = bestCarried(family.alternatives());
            if (carried != null || family.alternatives().size() == 1) {
                selectedVariants.put(family.groupId(), carried != null
                        ? carried : family.alternatives().getFirst());
                materialFamilyIndex++;
                continue;
            }
            selectingFamily = family;
            supplyPurpose = SupplyPurpose.SELECT_VARIANT;
            beginSupply(new SemanticMaterialSupplyCoordinator.Demand(
                    family.alternatives(), 1,
                    "select one obtainable material variant for " + family.identity()));
            return;
        }
        if (failureCode != null || prepared) return;
        try {
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

    private ResourceLocation bestCarried(List<ResourceLocation> alternatives) {
        ResourceLocation best = null;
        int bestCount = 0;
        for (ResourceLocation id : alternatives) {
            int count = inventoryCount(BuiltInRegistries.ITEM.get(id));
            if (count > bestCount || count == bestCount && count > 0
                    && (best == null || id.compareTo(best) < 0)) {
                best = id;
                bestCount = count;
            }
        }
        return best;
    }

    private void startBatchSupply(BatchNeed need) {
        supplyPurpose = SupplyPurpose.FETCH_BATCH;
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
        SemanticMaterialSupplyCoordinator.Tick tick = NavigationSafetyContext.withProtectedArea(
                plannedMutationCells, List.of(),
                () -> supply.tick(player, this::runChild));
        r.extendDeadlineTo(supply.childDeadline());
        if (tick.status() == SemanticMaterialSupplyCoordinator.Status.RUNNING) {
            return TaskState.RUNNING;
        }
        recordSupplyRound(supplyPurpose, tick);
        if (tick.status() == SemanticMaterialSupplyCoordinator.Status.FAILED) {
            stopWith(supplyPurpose == SupplyPurpose.SELECT_VARIANT
                            ? "material_variant_unavailable" : "material_batch_supply_failed",
                    tick.message(), tick.failureType());
            selectingFamily = null;
            supplyPurpose = null;
            return TaskState.FAILED;
        }
        if (supplyPurpose == SupplyPurpose.SELECT_VARIANT) {
            ResourceLocation selected = selectingFamily == null
                    ? null : bestCarried(selectingFamily.alternatives());
            if (selected == null) {
                stopWith("material_variant_selection_unobserved",
                        "generic supply reported success but no acceptable concrete build material "
                                + "was present in synchronized inventory",
                        FailureType.NO_MATERIAL);
                selectingFamily = null;
                supplyPurpose = null;
                return TaskState.FAILED;
            }
            selectedVariants.put(selectingFamily.groupId(), selected);
            materialFamilyIndex++;
            selectingFamily = null;
            supplyPurpose = null;
            advanceMaterialBinding();
            return failureCode == null ? TaskState.RUNNING : TaskState.FAILED;
        }
        supplyPurpose = null;
        return TaskState.RUNNING;
    }

    private void recordSupplyRound(
            SupplyPurpose purpose, SemanticMaterialSupplyCoordinator.Tick tick) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", purpose == SupplyPurpose.SELECT_VARIANT
                ? "material_variant" : "material_batch");
        value.put("terminal_state", tick.status().name().toLowerCase());
        value.put("remaining_cells", remainingCellCount());
        value.put("message", tick.message() == null ? "" : tick.message());
        if (tick.receipt() != null && !tick.receipt().isEmpty()) {
            value.put("supply", tick.receipt());
        }
        rounds.add(Map.copyOf(value));
    }

    private void startBuild() {
        buildRounds++;
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
        return verifyTraversability() ? TaskState.SUCCESS : TaskState.FAILED;
    }

    private boolean verifyTraversability() {
        if (activePlan.traversabilityContract() == null) {
            retainVerifiedPosition();
            return true;
        }
        traversabilityResult = BuildTraversabilityVerifier.verify(
                player.clientLevel, activePlan.traversabilityContract());
        if (traversabilityResult.valid()) {
            retainVerifiedPosition();
            return true;
        }
        stopWith(traversabilityResult.code(), traversabilityResult.message(), FailureType.NO_PATH);
        return false;
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
        data.put("goal_satisfied", allMatched() && traversalSatisfied);
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
        if (allMatched() && traversalSatisfied) {
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

    @Override protected void cleanup() {
        if (supply.active()) supply.cancel(player);
        if (activeChild != null) {
            activeChild.stop(player, Task.StopReason.REPLACED);
            activeChild.result(TaskState.CANCELLED);
            activeChild = null;
        }
        super.cleanup();
    }
}
