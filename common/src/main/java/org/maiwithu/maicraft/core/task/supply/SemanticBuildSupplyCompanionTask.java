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
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
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

/** Semantic material policy coordinator; physical effects remain in AE2 and Build child tasks. */
final class SemanticBuildSupplyCompanionTask
        extends AbstractCompanionTask<SemanticBuildSupplyTaskRecord> {
    private enum ChildKind { PREPARE, SUPPLY, BUILD }
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
                    "semantic storage batching currently accepts ordinary BuildTool block-item plans only",
                    FailureType.UNSUPPORTED);
            return;
        }
        refreshLedgers();
        if (allMatched()) {
            prepared = true;
            if (verifyTraversability()) succeed();
            return;
        }
        try {
            materialProposal = SemanticBuildMaterialBinding.propose(activePlan);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            stopWith("material_binding_invalid",
                    "construction stayed untouched because its semantic material families "
                            + "could not be represented safely: " + invalid.getMessage(),
                    FailureType.UNSUPPORTED);
            return;
        }
        if (materialProposal.empty()) {
            prepared = true;
        } else if (!Ae2ResourceSupply.available()) {
            stopWith("storage_adapter_unavailable",
                    "the complete material ledger cannot be proven because AE integration is unavailable: "
                            + Ae2ResourceSupply.availabilityDetail(), FailureType.NO_MATERIAL);
        } else {
            startAe(ChildKind.PREPARE, materialProposal.groups(),
                    Ae2ResourceSupply.Operation.PREPARE);
        }
    }

    @Override
    protected TaskState onTick() {
        if (activeChild != null) return tickChild();
        // Let the first-person child finish its receipt checks and scaffold cleanup before the
        // outer coordinator performs aggregate route verification.
        if (allMatched()) return finishMatched();
        if (!prepared) {
            stopWith("material_supply_unproven",
                    "construction stayed untouched because complete AE supply was not proven",
                    FailureType.NO_MATERIAL);
            return TaskState.FAILED;
        }

        BatchNeed need = nextNeed();
        if (need == null) {
            startBuild();
            return TaskState.RUNNING;
        }
        if (need.fetch() > 0) {
            startAe(ChildKind.SUPPLY,
                    List.of(new Ae2ResourceSupply.Group(itemId(need.item()), need.fetch())),
                    Ae2ResourceSupply.Operation.SUPPLY);
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
                // outward so a large healthy construction or AE preparation is not cut off by
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

        if (kind == ChildKind.PREPARE) {
            if (terminal == TaskState.SUCCESS && result != null && result.success()) {
                if (!bindPreparedPlan(result)) return TaskState.FAILED;
                prepared = true;
                return TaskState.RUNNING;
            }
            stopFromChild("material_supply_unproven",
                    "complete AE material supply was not proven, so construction did not start",
                    result, FailureType.NO_MATERIAL);
            return TaskState.FAILED;
        }
        if (kind == ChildKind.SUPPLY) {
            if (terminal == TaskState.SUCCESS && result != null && result.success()) {
                return TaskState.RUNNING;
            }
            stopFromChild("material_batch_supply_failed",
                    "an approved material batch was not confirmed in inventory",
                    result, FailureType.NO_MATERIAL);
            return TaskState.FAILED;
        }

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

    private void startAe(
            ChildKind kind, List<Ae2ResourceSupply.Group> groups,
            Ae2ResourceSupply.Operation operation) {
        long now = player.level().getGameTime();
        Ae2ResourceSupply.Request request = new Ae2ResourceSupply.Request(groups, true, operation);
        TaskRecord record = Ae2ResourceSupply.taskRecord(
                childId(operation.name().toLowerCase()), now + 20L * 60L * 20L, request);
        startChild(kind, record);
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

    private boolean bindPreparedPlan(TaskResult result) {
        try {
            Map<ResourceLocation, ResourceLocation> selected = selectedVariants(result);
            activePlan = SemanticBuildMaterialBinding.bind(r.plan, materialProposal, selected);
            refreshLedgers();
            return true;
        } catch (IllegalArgumentException invalid) {
            stopWith("material_variant_selection_invalid",
                    "construction stayed untouched because AE2 did not return one complete, "
                            + "reviewed material binding: " + invalid.getMessage(),
                    FailureType.NO_MATERIAL);
            return false;
        }
    }

    private Map<ResourceLocation, ResourceLocation> selectedVariants(TaskResult result) {
        if (materialProposal == null || materialProposal.empty()) {
            throw new IllegalArgumentException("no material proposal was awaiting selection");
        }
        Object rawDeltas = result.data().get("actual_delta");
        if (!(rawDeltas instanceof List<?> deltas)) {
            throw new IllegalArgumentException("the PREPARE result omitted actual_delta");
        }
        Map<ResourceLocation, SemanticBuildMaterialBinding.Family> expected =
                new LinkedHashMap<>();
        for (SemanticBuildMaterialBinding.Family family : materialProposal.families()) {
            expected.put(family.groupId(), family);
        }
        Map<ResourceLocation, ResourceLocation> selected = new LinkedHashMap<>();
        for (Object rawDelta : deltas) {
            if (!(rawDelta instanceof Map<?, ?> delta)
                    || !(delta.get("item_id") instanceof String groupText)
                    || !(delta.get("selected_item_id") instanceof String selectedText)) {
                throw new IllegalArgumentException(
                        "a PREPARE material group omitted its selected item identity");
            }
            ResourceLocation groupId = ResourceLocation.tryParse(groupText);
            ResourceLocation selectedId = ResourceLocation.tryParse(selectedText);
            if (groupId == null || selectedId == null || !expected.containsKey(groupId)) {
                throw new IllegalArgumentException(
                        "the PREPARE result contained an unknown material group");
            }
            if (selected.putIfAbsent(groupId, selectedId) != null) {
                throw new IllegalArgumentException(
                        "the PREPARE result repeated a material group selection");
            }
        }
        if (selected.size() != expected.size()) {
            throw new IllegalArgumentException(
                    "the PREPARE result selected " + selected.size() + " of "
                            + expected.size() + " material families");
        }
        return Map.copyOf(selected);
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
                value.put("data", kind == ChildKind.PREPARE
                        ? prepareRoundData(result.data()) : result.data());
            }
        }
        rounds.add(Map.copyOf(value));
    }

    /** Keep AE palette candidates and concrete terminal mechanics inside the Mod runtime. */
    private Map<String, Object> prepareRoundData(Map<String, Object> raw) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String key : List.of("failure_code", "status", "operation",
                "network_supply_prepared", "allow_crafting", "crafting_requests",
                "crafting_jobs_submitted", "effects_started", "outcome_uncertain",
                "mechanical_retry_allowed")) {
            if (raw.containsKey(key)) summary.put(key, raw.get(key));
        }
        Object deltas = raw.get("actual_delta");
        if (deltas instanceof List<?> values) {
            summary.put("material_family_count", values.size());
        }
        return Map.copyOf(summary);
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
        data.put("material_policy", "storage_available");
        data.put("complete_supply_prepared_before_construction", prepared);
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
                    Map.of("id", "repair_ae_supply", "risk", "none"),
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
        return "complete material ledger prepared, supplied in " + buildRounds
                + " carried batch(es), and every requested build cell"
                + (activePlan.traversabilityContract() == null ? "" : " and required route")
                + " re-verified";
    }

    @Override protected String timeoutMessage() {
        if (failureCode == null) failureCode = "semantic_build_supply_timeout";
        return "semantic build supply timed out; review the remaining ledger before retrying";
    }

    @Override protected void cleanup() {
        if (activeChild != null) {
            activeChild.stop(player, Task.StopReason.REPLACED);
            activeChild.result(TaskState.CANCELLED);
            activeChild = null;
        }
        super.cleanup();
    }
}
