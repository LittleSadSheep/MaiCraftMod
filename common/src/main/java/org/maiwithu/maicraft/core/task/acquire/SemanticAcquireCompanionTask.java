// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.combat.Swing;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.collect.CollectItemsTaskRecord;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.core.task.cook.SemanticCookTaskRecord;
import org.maiwithu.maicraft.core.task.craft.CraftPlanCost;
import org.maiwithu.maicraft.core.task.craft.CraftTaskRecord;
import org.maiwithu.maicraft.core.task.craft.CraftingWorkstationCoordinator;
import org.maiwithu.maicraft.core.task.entity.EntitySemanticSafety;
import org.maiwithu.maicraft.core.task.entity.GenericEntitySearchCompanionTask;
import org.maiwithu.maicraft.core.task.entity.GenericEntitySearchTaskRecord;
import org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord;
import org.maiwithu.maicraft.core.task.trade.SemanticTradeTaskRecord;
import org.maiwithu.maicraft.core.tools.CraftOps;
import org.maiwithu.maicraft.core.tools.RecipeProbe;
import org.maiwithu.maicraft.core.tools.ToolParse;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * A progress-driven semantic acquisition coordinator. It never performs a physical action itself: every
 * effect is a child task, and every transition is based on a terminal receipt plus a fresh live
 * main-inventory observation.
 */
public final class SemanticAcquireCompanionTask
        extends AbstractCompanionTask<SemanticAcquireTaskRecord> {
    private static final int MAX_REPORTED_ATTEMPTS = 64;
    private static final int MAX_REPORTED_ISSUES = 64;
    private static final int PLANNER_STEPS_PER_TICK = 1;
    private static final long PROGRESS_LEASE_TICKS = 60L * 20L;
    private static final long COLLECT_TICKS = 60L * 20L;
    private static final long MINE_MIN_TICKS = 60L * 20L;
    private static final long MINE_PER_UNIT_TICKS = 30L * 20L;
    private static final long STORAGE_TICKS = 10L * 60L * 20L;
    private static final long HUNT_TICKS = 120L * 20L;
    private static final int HUNT_SEARCH_DISTANCE = 512;
    private static final int STRUCTURAL_RECIPE_DEPTH = 6;
    private static final int UNREACHABLE_STRUCTURE_COST = 1_000_000;

    private enum HuntChildStage { NONE, SEARCH, ATTACK }

    private static final class Need {
        final List<ResourceLocation> itemIds;
        final int requiredFinalCount;
        final int depth;
        final Set<ResourceLocation> lineageItems;
        final Set<String> lineageRecipes;
        /** Every parent recipe that this one-unit alternative frontier can unlock. */
        final Set<String> parentRecipeIds;
        final List<SemanticAcquireTaskRecord.Source> allowedSources;
        final Set<String> rejectedRecipes = new LinkedHashSet<>();
        /** Recipes for which one concrete local crafting-surface recovery was already inserted. */
        final Set<String> surfaceRecoveryRecipes = new LinkedHashSet<>();
        final Map<SemanticAcquireTaskRecord.Source, Integer> sourceAttempts =
                new LinkedHashMap<>();
        final Set<ResourceLocation> rejectedTradeOutputs = new LinkedHashSet<>();
        final Set<String> exhaustedHuntSearchStates = new LinkedHashSet<>();
        final Set<SemanticAcquireTaskRecord.Source> exhaustedSources = new LinkedHashSet<>();
        /** Once prerequisite work starts, execution stays on this recipe identity. */
        final Set<String> committedRecipeIds = new LinkedHashSet<>();
        boolean committedRecipeEffectsObserved;
        List<SemanticAcquireTaskRecord.Source> plannedSourceOrder;
        int huntSearchAttempts;
        boolean huntSearchExpandedView;
        boolean miningToolPrerequisitePushed;
        int preferredToolTierCap = 3;
        boolean stockOnlyTool;
        boolean toolPrerequisite;
        boolean efficientBatchStarted;
        boolean effectsObserved;
        boolean decisionRequired;
        ResourceLocation preferredTradeOutput;
        int lastObservedCount = -1;

        Need(
                List<ResourceLocation> itemIds,
                int requiredFinalCount,
                int depth,
                Set<ResourceLocation> lineageItems,
                Set<String> lineageRecipes,
                Set<String> parentRecipeIds,
                List<SemanticAcquireTaskRecord.Source> allowedSources) {
            this.itemIds = List.copyOf(itemIds);
            this.requiredFinalCount = requiredFinalCount;
            this.depth = depth;
            this.lineageItems = Set.copyOf(lineageItems);
            this.lineageRecipes = Set.copyOf(lineageRecipes);
            this.parentRecipeIds = parentRecipeIds == null
                    ? Set.of() : Set.copyOf(parentRecipeIds);
            this.allowedSources = List.copyOf(allowedSources);
        }

        int attempts(SemanticAcquireTaskRecord.Source source) {
            return sourceAttempts.getOrDefault(source, 0);
        }

        void attempted(SemanticAcquireTaskRecord.Source source) {
            sourceAttempts.merge(source, 1, Integer::sum);
        }
    }

    private record CraftCandidate(
            ResourceLocation outputItem,
            String recipeId,
            Map<String, Object> data,
            CraftPlanCost cost,
            boolean surfaceSupported,
            boolean surfaceReady,
            List<ResourceLocation> surfacePrerequisiteItems) {}

    private record ExecutableCraft(ResourceLocation outputItem, CraftOps.Plan plan) {}

    private record IngredientNeed(
            List<ResourceLocation> itemIds, int missing, Map<String, Object> fact) {}

    private record CraftFrontier(
            IngredientNeed ingredient,
            Set<String> recipeIds,
            List<ResourceLocation> outputItemIds) {}

    private record RankedIngredient(
            IngredientNeed ingredient, boolean decisionRequired, int structureCost) {}

    private record StructureKey(
            ResourceLocation itemId,
            int remainingDepth,
            Set<ResourceLocation> blocked) {
        private StructureKey {
            blocked = Set.copyOf(blocked);
        }
    }

    private record NearbySurvey(
            int safeCount,
            int protectedCount,
            List<Map<String, Object>> protectedSamples) {}

    private record DimensionBarrier(
            List<ResourceLocation> itemIds,
            List<ResourceLocation> allowedDimensions,
            ResourceLocation currentDimension,
            int depth) {
        DimensionBarrier {
            itemIds = List.copyOf(itemIds);
            allowedDimensions = List.copyOf(new LinkedHashSet<>(allowedDimensions));
        }
    }

    private final CraftOps craftOps = new CraftOps();
    private final Deque<Need> needs = new ArrayDeque<>();
    private final List<Map<String, Object>> attempts = new ArrayList<>();
    private final List<Map<String, Object>> issues = new ArrayList<>();
    private final List<Map<String, Object>> recipeTrace = new ArrayList<>();
    private final List<DimensionBarrier> dimensionBarriers = new ArrayList<>();
    private Map<ResourceLocation, List<CraftingRecipe>> structuralCraftRecipes;
    private Map<ResourceLocation, Integer> initialCounts = Map.of();
    private Need rootNeed;
    private Task activeChild;
    private TaskRecord activeRecord;
    private Need activeNeed;
    private SemanticAcquireTaskRecord.Source activeSource;
    private String activeDetail;
    private int activeBeforeCount;
    private int activeLastObservedCount;
    private Map<ResourceLocation, Integer> activeBeforeInventory = Map.of();
    private HuntChildStage activeHuntStage = HuntChildStage.NONE;
    private UUID activeHuntTarget;
    private final Set<UUID> rejectedHuntTargets = new LinkedHashSet<>();
    private int harmlessHuntRetargets;
    private int childSerial;
    private int plannerStepsThisTick;
    private String failureCode;
    private Need failureNeed;
    private DimensionBarrier failureDimension;
    private boolean outcomeUncertain;

    public SemanticAcquireCompanionTask(
            LocalPlayer player, SemanticAcquireTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        initialCounts = counts(r.itemIds);
        rootNeed = new Need(
                r.itemIds,
                r.count,
                0,
                new LinkedHashSet<>(r.itemIds),
                Set.of(),
                Set.of(),
                r.allowedSources);
        rootNeed.lastObservedCount = count(rootNeed.itemIds);
        needs.push(rootNeed);
    }

    @Override
    protected TaskState onTick() {
        AcquisitionProtection protection = AcquisitionProtection.resolve(r.protectedLabels,
                IntentRuntime.get().landmarks(), player.level().dimension().location().toString());
        if (!protection.problems().isEmpty()) {
            return failAcquisition("unresolved_protected_label",
                    String.join("; ", protection.problems()), FailureType.TARGET_LOST);
        }
        return protection.run(this::tickAcquisition);
    }

    private TaskState tickAcquisition() {
        plannerStepsThisTick = 0;
        // This check deliberately precedes child advancement. A child may have made the semantic
        // fact true on the previous tick; no menu cleanup, recipe branch or mining swing is allowed
        // to continue merely because its internal task has not yet declared terminal success. The
        // sole exception is an effect already in flight behind a terminal barrier: that exact child
        // is allowed to settle its receipt and close its menu, but not to start another effect.
        if (count(r.itemIds) >= r.count) {
            if (activeChild != null
                    && activeChild.mustSettleBeforeSatisfiedCancellation()) {
                return tickActiveChild();
            }
            cancelActiveBecauseSatisfied();
            return TaskState.SUCCESS;
        }

        if (activeChild != null) {
            return tickActiveChild();
        }

        if (needs.isEmpty()) {
            return failAcquisition(
                    "internal_need_stack_empty",
                    "the acquisition planner lost its remaining need before the inventory fact was true",
                    FailureType.INTERNAL);
        }

        Need need = needs.peek();
        int observedNeedCount = count(need.itemIds);
        if (need.lastObservedCount >= 0 && observedNeedCount > need.lastObservedCount) {
            need.plannedSourceOrder = null;
            renewProgressLease();
        }
        need.lastObservedCount = observedNeedCount;
        if (observedNeedCount >= need.requiredFinalCount) {
            Need satisfied = needs.pop();
            propagateSatisfiedNeed(satisfied);
            renewProgressLease();
            return TaskState.RUNNING;
        }
        if (need.decisionRequired) {
            if (need.depth > 0) return exhaustNeed(need);
            failureNeed = need;
            return failAcquisition(
                    "acquisition_decision_required",
                    "the remaining direct source reached a permission or protection boundary; "
                            + "MaiCraft stopped for narration before trying another effectful route",
                    FailureType.NO_MATERIAL);
        }
        List<SemanticAcquireTaskRecord.Source> sourceOrder = executionSourceOrder(need);
        if (sourceOrder.isEmpty()) {
            return exhaustNeed(need);
        }

        SemanticAcquireTaskRecord.Source source = sourceOrder.getFirst();
        return switch (source) {
            case INVENTORY -> observeInventorySource(need);
            case NEARBY -> attemptNearby(need);
            case STORAGE -> attemptStorage(need);
            case CRAFT -> attemptCraft(need);
            case COOK -> attemptCook(need);
            case MINE -> attemptMine(need);
            case TRADE -> reviewTrade(need);
            case HUNT -> reviewHunt(need);
        };
    }

    private TaskState observeInventorySource(Need need) {
        addIssue("inventory", "inventory_insufficient",
                "the final inventory fact is not yet true",
                needFacts(need));
        advanceSource(need);
        return TaskState.RUNNING;
    }

    private TaskState attemptNearby(Need need) {
        if (!sourceDimensionAllowed(need, SemanticAcquireTaskRecord.Source.NEARBY)) {
            return TaskState.RUNNING;
        }
        if (!takePlannerStep()) return TaskState.RUNNING;
        need.attempted(SemanticAcquireTaskRecord.Source.NEARBY);
        NearbySurvey survey = surveyNearby(need.itemIds);
        if (survey.protectedCount() > 0) {
            addIssue("nearby", "drop_ownership_ambiguous",
                    "matching loose items include another entity's drops or a remembered/protected place; "
                            + "the generic collector cannot safely include one and exclude another",
                    Map.of("safe_matching_drops", survey.safeCount(),
                            "protected_matching_drops", survey.protectedCount(),
                            "protected_samples", survey.protectedSamples()));
            advanceSource(need);
            return TaskState.RUNNING;
        }
        if (survey.safeCount() == 0) {
            addIssue("nearby", "no_safe_drop_evidence",
                    "no loaded, pickup-ready, provably unowned matching drops were observed",
                    Map.of("radius", r.searchRadius));
            advanceSource(need);
            return TaskState.RUNNING;
        }

        Set<Item> items = need.itemIds.stream()
                .map(BuiltInRegistries.ITEM::get)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        long now = player.level().getGameTime();
        CollectItemsTaskRecord record = new CollectItemsTaskRecord(
                childId("nearby"), now + COLLECT_TICKS, items, r.searchRadius,
                shortLabel(need.itemIds));
        return startChild(need, SemanticAcquireTaskRecord.Source.NEARBY,
                record, "collect loaded unowned drops");
    }

    private TaskState attemptStorage(Need need) {
        if (!Ae2ResourceSupply.available()) {
            addIssue("storage", "storage_adapter_unavailable",
                    "no supported client storage-network adapter is available",
                    Map.of("detail", Ae2ResourceSupply.availabilityDetail()));
            advanceSource(need);
            return TaskState.RUNNING;
        }
        int missing = missing(need);
        if (missing <= 0) return TaskState.RUNNING;
        if (!takePlannerStep()) return TaskState.RUNNING;
        need.attempted(SemanticAcquireTaskRecord.Source.STORAGE);
        Ae2ResourceSupply.Group group = new Ae2ResourceSupply.Group(
                need.itemIds.getFirst(), need.itemIds, missing,
                Ae2ResourceSupply.SelectionMode.AGGREGATE);
        boolean allowNetworkCrafting = r.allowedSources.contains(
                SemanticAcquireTaskRecord.Source.CRAFT);
        Ae2ResourceSupply.Request request = new Ae2ResourceSupply.Request(
                List.of(group), allowNetworkCrafting);
        long now = player.level().getGameTime();
        TaskRecord record = Ae2ResourceSupply.taskRecord(
                childId("storage"), now + STORAGE_TICKS, request);
        return startChild(need, SemanticAcquireTaskRecord.Source.STORAGE,
                record, "request exact missing aggregate count from storage"
                        + (allowNetworkCrafting ? " with network crafting allowed" : ""));
    }

    private TaskState attemptCraft(Need need) {
        int deficit = missing(need);
        List<CraftCandidate> candidates = new ArrayList<>();
        List<ExecutableCraft> executable = new ArrayList<>();
        CraftingWorkstationCoordinator.PlanningSnapshot workstation =
                CraftOps.requiresWorkstationForAny(need.itemIds, player)
                        ? CraftingWorkstationCoordinator.inspect(player) : null;
        if (workstation != null && workstation.surface() == CraftPlanCost.Surface.SEARCHING) {
            return TaskState.RUNNING;
        }
        Set<String> excludedRecipes = new LinkedHashSet<>(need.lineageRecipes);
        excludedRecipes.addAll(need.rejectedRecipes);
        // A commitment prevents speculative recursion from hopping between incomplete routes. It
        // must not hide a different route whose complete material condition is now true: using
        // already-carried inputs is strictly less work than extending the old prerequisite chain.
        // One comparison round is one planner unit. Charging once per tag member made the result
        // depend on registry order and could stop before the cheapest satisfiable alternative.
        if (!takePlannerStep()) return TaskState.RUNNING;
        for (ResourceLocation output : need.itemIds) {
            int requestedOwnFinal = PlayerInv.buildableCount(
                    player.getInventory(), BuiltInRegistries.ITEM.get(output)) + deficit;
            ToolContext context = new ToolContext(
                    childId("craft-plan"), player.level().getGameTime());
            CraftOps.Plan plan = craftOps.plan(
                    output.toString(), requestedOwnFinal, player, context,
                    workstation, excludedRecipes);
            if (plan.executable()) {
                executable.add(new ExecutableCraft(output, plan));
            } else {
                collectCraftCandidates(output, plan, candidates);
            }
        }

        ExecutableCraft selected = executable.stream()
                .filter(candidate -> candidate.plan().cost() != null)
                .min(Comparator.comparing(
                        candidate -> candidate.plan().cost(), CraftPlanCost.ORDER))
                .orElse(null);
        if (selected != null) {
            // Material-complete alternatives supersede an incomplete commitment. Commitment only
            // prevents speculative prerequisite hopping; it may never force more acquisition
            // after another acceptable recipe is already executable from live inventory.
            need.committedRecipeIds.clear();
            need.committedRecipeIds.add(selected.plan().task().recipeId.toString());
            need.committedRecipeEffectsObserved = false;
            need.attempted(SemanticAcquireTaskRecord.Source.CRAFT);
            return startChild(need, SemanticAcquireTaskRecord.Source.CRAFT,
                    selected.plan().task(),
                    "craft " + selected.outputItem() + " via the cheapest live satisfiable path");
        }

        CraftCandidate surfacePrerequisite = candidates.stream()
                .filter(candidate -> recipeAllowedByCommit(need, candidate.recipeId()))
                .filter(candidate -> !need.rejectedRecipes.contains(candidate.recipeId()))
                .filter(candidate -> !need.lineageRecipes.contains(candidate.recipeId()))
                .filter(CraftCandidate::surfaceSupported)
                .filter(candidate -> candidate.cost().missingMaterials() == 0)
                .filter(candidate -> candidate.cost().surface()
                        == CraftPlanCost.Surface.PREREQUISITE)
                .filter(candidate -> !candidate.surfacePrerequisiteItems().isEmpty())
                .sorted(Comparator.comparing(CraftCandidate::cost, CraftPlanCost.ORDER))
                .findFirst().orElse(null);
        if (surfacePrerequisite != null
                && pushCraftingSurfacePrerequisite(need, surfacePrerequisite)) {
            return TaskState.RUNNING;
        }

        List<CraftCandidate> viableCandidates = candidates.stream()
                .filter(candidate -> recipeAllowedByCommit(need, candidate.recipeId()))
                .filter(candidate -> !need.rejectedRecipes.contains(candidate.recipeId()))
                .filter(candidate -> !need.lineageRecipes.contains(candidate.recipeId()))
                .filter(CraftCandidate::surfaceSupported)
                .filter(candidate -> candidate.cost().missingMaterials() > 0)
                // A conversion whose only missing inputs are already members of this need's
                // ancestry cannot advance the inventory fact. Skip the dominated/cyclical route
                // as a set instead of reporting every stripped-log/wood recipe one by one.
                .filter(candidate -> chooseIngredient(candidate, need) != null)
                .sorted(Comparator
                        .comparingInt((CraftCandidate candidate) ->
                                candidate.cost().surface().ordinal())
                        .thenComparingInt(candidate ->
                                recursiveCandidateStructureCost(candidate, need))
                        .thenComparing(CraftCandidate::cost, CraftPlanCost.ORDER))
                .toList();
        CraftCandidate chosen = viableCandidates.isEmpty()
                ? null : viableCandidates.getFirst();
        if (chosen == null) {
            if (!need.committedRecipeIds.isEmpty()) {
                if (need.committedRecipeEffectsObserved) {
                    addIssue("craft", "committed_recipe_unavailable",
                            "the selected recipe stopped exposing a complete frontier after "
                                    + "inventory or world effects were observed",
                            Map.of("recipe_ids", List.copyOf(need.committedRecipeIds),
                                    "requires_narration", true,
                                    "partial_effects", true));
                    failureNeed = need;
                    return failAcquisition(
                            "committed_recipe_unavailable",
                            "the committed recipe stopped exposing a complete cycle-free prerequisite "
                                    + "frontier after effects were observed; MaiCraft stopped instead of "
                                    + "silently switching routes",
                            FailureType.NO_MATERIAL);
                }
                need.rejectedRecipes.addAll(need.committedRecipeIds);
                need.committedRecipeIds.clear();
                need.committedRecipeEffectsObserved = false;
                return TaskState.RUNNING;
            }
            boolean surfaceMissing = candidates.stream().anyMatch(candidate ->
                    candidate.cost().missingMaterials() == 0
                            && candidate.surfaceSupported() && !candidate.surfaceReady());
            addIssue("craft", surfaceMissing
                            ? "crafting_surface_missing" : "no_finite_recipe_path",
                    surfaceMissing
                            ? "materials exist, but no compatible crafting surface can be prepared from live facts"
                            : "no cycle-free client-known ordinary recipe path remained",
                    Map.of("candidate_count", candidates.size(),
                            "rejected_recipes", List.copyOf(need.rejectedRecipes),
                            "lineage_recipes", List.copyOf(need.lineageRecipes)));
            advanceSource(need);
            return TaskState.RUNNING;
        }

        CraftFrontier frontier = chooseCraftFrontier(chosen, viableCandidates, need);
        if (frontier == null) {
            need.rejectedRecipes.add(chosen.recipeId());
            addIssue("craft", "recipe_cycle_or_missing_evidence",
                    "the closest recipe's missing ingredients were cyclical or did not expose "
                            + "acceptable item IDs",
                    Map.of("recipe_id", chosen.recipeId()));
            return TaskState.RUNNING;
        }
        IngredientNeed ingredient = frontier.ingredient();
        commitRecipe(need, frontier.recipeIds());
        Set<ResourceLocation> lineageItems = new LinkedHashSet<>(need.lineageItems);
        lineageItems.addAll(ingredient.itemIds());
        Set<String> lineageRecipes = new LinkedHashSet<>(need.lineageRecipes);
        lineageRecipes.addAll(frontier.recipeIds());
        int ingredientFinal = count(ingredient.itemIds()) + ingredient.missing();
        boolean mergedAlternativeFrontier = frontier.recipeIds().size() > 1;
        List<SemanticAcquireTaskRecord.Source> childSources = mergedAlternativeFrontier
                ? prioritizeDirectAlternativeSources(need.allowedSources)
                : need.allowedSources;
        Need childNeed = new Need(
                ingredient.itemIds(), ingredientFinal, need.depth + 1,
                lineageItems, lineageRecipes, frontier.recipeIds(), childSources);
        childNeed.lastObservedCount = count(childNeed.itemIds);
        recipeTrace.add(Map.of(
                "recipe_id", chosen.recipeId(),
                "output_item_id", chosen.outputItem().toString(),
                "alternative_recipe_ids", List.copyOf(frontier.recipeIds()),
                "alternative_output_item_ids", itemStrings(frontier.outputItemIds()),
                "depth", need.depth,
                "missing_item_ids", itemStrings(ingredient.itemIds()),
                "missing_count", ingredient.missing(),
                "source_order", childSources.stream()
                        .map(source -> source.name().toLowerCase(java.util.Locale.ROOT))
                        .toList()));
        needs.push(childNeed);
        renewProgressLease();
        return TaskState.RUNNING;
    }

    private TaskState attemptMine(Need need) {
        if (!sourceDimensionAllowed(need, SemanticAcquireTaskRecord.Source.MINE)) {
            return TaskState.RUNNING;
        }
        List<String> refs = new ArrayList<>();
        for (ResourceLocation itemId : need.itemIds) {
            Item item = BuiltInRegistries.ITEM.get(itemId);
            if (item instanceof BlockItem blockItem) {
                refs.add(BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString());
            }
        }
        refs.addAll(sourceHint(need).blockRefs());
        Set<Block> blocks = ToolParse.parseBlocks(refs);
        if (blocks.isEmpty()) {
            addIssue("mine", "mine_source_evidence_missing",
                    "no source block can be derived from a requested BlockItem and no explicit "
                            + "semantic block source_hint was supplied",
                    Map.of("requested_item_ids", itemStrings(need.itemIds)));
            advanceSource(need);
            return TaskState.RUNNING;
        }
        WorkToolPreparation.Choice workTool = WorkToolPreparation.missing(player, blocks,
                need.efficientBatchStarted ? Math.max(WorkToolPreparation.BATCH_SIZE, missing(need)) : missing(need),
                toolMaterialBudget(Items.IRON_INGOT), toolMaterialBudget(Items.DIAMOND),
                need.preferredToolTierCap);
        int bootstrap = workTool != null && !workTool.stockOnly()
                ? WorkToolPreparation.bootstrapLimit(player, blocks) : 0;
        SemanticSourceKnowledge.ToolRequirement tool = SemanticSourceKnowledge.missingTool(player, blocks);
        if (tool == null && bootstrap == 0 && workTool != null) tool = workTool.requirement();
        boolean stockOnlyUpgrade = workTool != null && tool == workTool.requirement() && workTool.stockOnly();
        if (tool != null) {
            if (need.miningToolPrerequisitePushed) {
                addIssue("mine", "wrong_tool_prerequisite_unmet",
                        "source blocks are known, but the recursively requested harvesting tool "
                                + "is still absent",
                        Map.of("tool_family", tool.toolFamily(),
                                "minimum_tier", tool.minimumTier(),
                                "acceptable_tool_count", tool.acceptableItemIds().size()));
                advanceSource(need);
                return TaskState.RUNNING;
            }
            List<ResourceLocation> toolItems = tool.acceptableItemIds().stream()
                    .filter(id -> !need.lineageItems.contains(id))
                    .toList();
            if (toolItems.isEmpty()) {
                addIssue("mine", "tool_recipe_cycle",
                        "every suitable harvesting-tool alternative is already in this finite prerequisite lineage",
                        Map.of("tool_family", tool.toolFamily(),
                                "minimum_tier", tool.minimumTier()));
                advanceSource(need);
                return TaskState.RUNNING;
            }
            Set<ResourceLocation> lineage = new LinkedHashSet<>(need.lineageItems);
            lineage.addAll(toolItems);
            List<SemanticAcquireTaskRecord.Source> toolSources = new ArrayList<>(need.allowedSources);
            // Source restrictions describe how to obtain the requested material. Making its
            // work tool remains a prerequisite, even for a mine-only material request.
            if (!toolSources.contains(SemanticAcquireTaskRecord.Source.CRAFT))
                toolSources.add(SemanticAcquireTaskRecord.Source.CRAFT);
            if (stockOnlyUpgrade) {
                toolSources = new ArrayList<>(List.of(SemanticAcquireTaskRecord.Source.INVENTORY,
                        SemanticAcquireTaskRecord.Source.CRAFT));
                if (StockEvidence.latest(player).map(StockEvidence.Snapshot::supportsToolSupply).orElse(false))
                    toolSources.add(SemanticAcquireTaskRecord.Source.STORAGE);
            }
            Need toolNeed = new Need(
                    toolItems, count(toolItems) + 1,
                    need.depth + 1, lineage, need.lineageRecipes, Set.of(),
                    toolSources);
            toolNeed.stockOnlyTool = stockOnlyUpgrade;
            toolNeed.toolPrerequisite = true;
            toolNeed.lastObservedCount = count(toolNeed.itemIds);
            need.miningToolPrerequisitePushed = true;
            addIssue("mine", "preparing_harvesting_tool",
                    "prepare a durable efficient tool before this batch, using available materials",
                    Map.of("tool_family", tool.toolFamily(),
                            "minimum_tier", tool.minimumTier(),
                            "acceptable_tool_count", tool.acceptableItemIds().size()));
            needs.push(toolNeed);
            renewProgressLease();
            return TaskState.RUNNING;
        }
        if (!takePlannerStep()) return TaskState.RUNNING;
        need.attempted(SemanticAcquireTaskRecord.Source.MINE);
        need.miningToolPrerequisitePushed = false;
        int deficit = WorkToolPreparation.batchLimit(player, blocks, Math.min(256, missing(need)));
        if (bootstrap > 0) deficit = Math.min(deficit, bootstrap);
        long now = player.level().getGameTime();
        long budget = Math.max(MINE_MIN_TICKS, deficit * MINE_PER_UNIT_TICKS);
        Set<Item> progressItems = need.itemIds.stream()
                .map(BuiltInRegistries.ITEM::get)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        boolean efficient = MineBlockTaskRecord.hasEfficientTool(player, blocks);
        need.efficientBatchStarted |= efficient && bootstrap == 0 && missing(need) >= WorkToolPreparation.BATCH_SIZE;
        MineBlockTaskRecord record = new MineBlockTaskRecord(
                childId("mine"), now + budget, blocks, deficit, blockLabel(blocks),
                progressItems, efficient);
        return startChild(need, SemanticAcquireTaskRecord.Source.MINE,
                record, "mine BlockItem-derived or semantic source blocks");
    }

    private long toolMaterialBudget(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        int reserved = needs.stream().filter(need -> need.itemIds.contains(id))
                .mapToInt(need -> need.requiredFinalCount).max().orElse(0);
        long carried = PlayerInv.buildableCount(player.getInventory(), item);
        long external = StockEvidence.latest(player).map(stock -> stock.storedCount(id)).orElse(0L);
        return Math.max(0, carried + Math.min(external, Long.MAX_VALUE - carried) - reserved);
    }

    private TaskState attemptCook(Need need) {
        ResourceLocation output = need.itemIds.stream()
                .filter(this::hasCookingRecipe)
                .findFirst().orElse(null);
        if (output == null) {
            addIssue("cook", "no_cooking_source_path",
                    "no client-known furnace-family recipe produces this recursive need",
                    Map.of("requested_item_ids", itemStrings(need.itemIds)));
            advanceSource(need);
            return TaskState.RUNNING;
        }
        if (!takePlannerStep()) return TaskState.RUNNING;
        need.attempted(SemanticAcquireTaskRecord.Source.COOK);
        int currentSelected = PlayerInv.buildableCount(
                player.getInventory(), BuiltInRegistries.ITEM.get(output));
        int selectedFinal = Math.min(
                SemanticCookTaskRecord.MAX_FINAL_COUNT, currentSelected + missing(need));
        List<SemanticAcquireTaskRecord.Source> childSources = r.allowedSources.stream()
                .filter(source -> source != SemanticAcquireTaskRecord.Source.COOK)
                .toList();
        long now = player.level().getGameTime();
        SemanticCookTaskRecord child = new SemanticCookTaskRecord(
                childId("cook"),
                now + 12L * 60L * 20L,
                output, selectedFinal, SemanticCookTaskRecord.Preference.AUTO,
                List.of(), childSources, r.allowHarm, r.protectedLabels);
        return startChild(need, SemanticAcquireTaskRecord.Source.COOK, child,
                "cook a client-known output while recursively acquiring its prerequisites");
    }

    private boolean hasCookingRecipe(ResourceLocation outputId) {
        Item output = BuiltInRegistries.ITEM.get(outputId);
        for (var holder : ClientRuntime.requireContext(player)
                .connection().getRecipeManager().getRecipes()) {
            if (!(holder.value() instanceof AbstractCookingRecipe cooking)) continue;
            ItemStack result = RecipeProbe.resultOf(
                    cooking, player.level().registryAccess());
            if (!result.isEmpty() && result.is(output)) return true;
        }
        return false;
    }

    private TaskState reviewTrade(Need need) {
        if (!takePlannerStep()) return TaskState.RUNNING;
        ResourceLocation output = need.preferredTradeOutput;
        if (output == null || need.rejectedTradeOutputs.contains(output)) {
            output = need.itemIds.stream()
                    .filter(id -> !need.rejectedTradeOutputs.contains(id))
                    .findFirst().orElse(null);
            need.preferredTradeOutput = output;
        }
        if (output == null) {
            addIssue("trade", "trade_alternatives_exhausted",
                    "every acceptable output alternative has returned a structured failure or no inventory increment",
                    Map.of("alternative_count", need.itemIds.size(),
                            "rejected_outputs", itemStrings(
                                    List.copyOf(need.rejectedTradeOutputs))));
            advanceSource(need);
            return TaskState.RUNNING;
        }
        need.attempted(SemanticAcquireTaskRecord.Source.TRADE);
        int currentSelected = PlayerInv.buildableCount(
                player.getInventory(), BuiltInRegistries.ITEM.get(output));
        int selectedFinalCount = Math.min(
                SemanticTradeTaskRecord.MAX_FINAL_COUNT,
                currentSelected + missing(need));
        long now = player.level().getGameTime();
        SemanticTradeTaskRecord record = new SemanticTradeTaskRecord(
                childId("trade"),
                now + 8L * 60L * 20L,
                output,
                selectedFinalCount,
                SemanticTradeTaskRecord.MerchantKind.AUTO,
                List.of(),
                r.protectedLabels,
                Math.min(r.searchRadius, SemanticTradeTaskRecord.MAX_RADIUS));
        return startChild(need, SemanticAcquireTaskRecord.Source.TRADE, record,
                "inspect loaded unprotected merchants and execute a synchronized affordable offer");
    }

    private TaskState reviewHunt(Need need) {
        if (!r.allowHarm) {
            SemanticAcquireTaskRecord.SourceHint hint = sourceHint(need);
            addIssue("hunt", "harm_permission_required",
                    "hunt was allowed as a source family, but allow_harm is false; no entity was attacked",
                    Map.of("requires_narration", true,
                            "ingredient_item_ids", itemStrings(need.itemIds),
                            "entity_type_ids", stringIds(hint.entityTypeIds()),
                            "expected_item_ids", stringIds(hint.expectedItemIds()),
                            "description", hint.description() == null
                                    ? "semantic entity source" : hint.description()));
            need.decisionRequired = true;
            advanceSource(need);
            return TaskState.RUNNING;
        }
        SemanticAcquireTaskRecord.SourceHint hint = sourceHint(need);
        if (hint.entityTypeIds().isEmpty()) {
            addIssue("hunt", "entity_source_evidence_missing",
                    "no semantic entity type source_hint was supplied; no entity was selected",
                    Map.of());
            advanceSource(need);
            return TaskState.RUNNING;
        }
        Set<ResourceLocation> expected = new LinkedHashSet<>(hint.expectedItemIds());
        expected.retainAll(need.itemIds);
        if (expected.isEmpty()) {
            addIssue("hunt", "drop_relation_evidence_missing",
                    "the semantic hint does not identify any requested item as an expected product; "
                            + "no entity was attacked",
                    Map.of("entity_type_ids", stringIds(hint.entityTypeIds())));
            advanceSource(need);
            return TaskState.RUNNING;
        }
        if (!sourceDimensionAllowed(need, SemanticAcquireTaskRecord.Source.HUNT)) {
            return TaskState.RUNNING;
        }

        GenericEntitySearchTaskRecord.Relation relation =
                SemanticSourceKnowledge.huntRelation(hint.entityTypeIds());
        List<Map<String, Object>> protectedCandidates = new ArrayList<>();
        int loadedRadius = need.huntSearchExpandedView
                ? GenericEntitySearchCompanionTask.LOADED_EVIDENCE_RADIUS
                : r.searchRadius;
        List<Entity> safe = safeLoadedHuntCandidates(
                hint, relation, loadedRadius, protectedCandidates);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("safe_candidate_count", safe.size());
        facts.put("protected_or_ambiguous_candidate_count", protectedCandidates.size());
        facts.put("protected_candidate_samples", protectedCandidates.stream().limit(8).toList());
        facts.put("expected_item_ids", stringIds(expected));
        facts.put("relation", relation.name().toLowerCase(java.util.Locale.ROOT));
        facts.put("searched_loaded_radius", loadedRadius);
        if (safe.isEmpty()) {
            if (!protectedCandidates.isEmpty()) {
                addIssue("hunt", "protected_hunt_targets_skipped",
                        "loaded matching entities were excluded because they have explicit "
                                + "protection evidence: they are "
                                + "named, tamed, owned, leashed, in a vehicle, carrying a passenger, "
                                + "or inside an explicitly protected enclosed area",
                        facts);
            }
            String searchState = huntSearchState(need, hint, relation);
            if (!need.exhaustedHuntSearchStates.add(searchState)) {
                addIssue("hunt", "hunt_entity_search_state_repeated",
                        "the same dimension, position and semantic entity search state produced no new evidence",
                        Map.of("search_attempts", need.huntSearchAttempts,
                                "max_search_distance", HUNT_SEARCH_DISTANCE,
                                "entity_type_ids", stringIds(hint.entityTypeIds()),
                                "relation", relation.name().toLowerCase(java.util.Locale.ROOT)));
                advanceSource(need);
                return TaskState.RUNNING;
            }
            if (!takePlannerStep()) return TaskState.RUNNING;
            need.huntSearchAttempts++;
            need.huntSearchExpandedView = false;
            addIssue("hunt", "no_loaded_hunt_evidence_searching",
                    "no acceptable target is currently loaded; starting first-person frontier search",
                    facts);
            long now = player.level().getGameTime();
            GenericEntitySearchTaskRecord search = new GenericEntitySearchTaskRecord(
                    childId("hunt-search"),
                    now + 10L * 60L * 20L,
                    hint.entityTypeIds(), relation, 1, HUNT_SEARCH_DISTANCE,
                    false, r.protectedLabels, true, rejectedHuntTargets);
            return startHuntChild(need, search, HuntChildStage.SEARCH, null,
                    "find an unprotected semantic hunt source through first-person exploration");
        }

        if (!takePlannerStep()) return TaskState.RUNNING;
        need.attempted(SemanticAcquireTaskRecord.Source.HUNT);
        Entity target = safe.getFirst();
        long now = player.level().getGameTime();
        AttackTaskRecord attack = new AttackTaskRecord(
                childId("hunt-attack"), now + HUNT_TICKS,
                List.of(target.getId()), false, true);
        return startHuntChild(need, attack, HuntChildStage.ATTACK, target.getUUID(),
                "hunt one loaded unprotected "
                        + BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()));
    }

    /**
     * One source of truth for both initial hunt selection and harmless pre-strike retargeting.
     * The returned order is the same nearest-first loaded-evidence order used by reviewHunt.
     */
    private List<Entity> safeLoadedHuntCandidates(
            SemanticAcquireTaskRecord.SourceHint hint,
            GenericEntitySearchTaskRecord.Relation relation,
            int loadedRadius,
            List<Map<String, Object>> protectedCandidates) {
        List<Entity> safe = new ArrayList<>();
        AABB box = player.getBoundingBox().inflate(loadedRadius);
        for (Entity entity : player.clientLevel.getEntities(player, box, candidate ->
                candidate != player && !candidate.isRemoved() && candidate.isAlive()
                        && candidate.isAttackable()
                        && !rejectedHuntTargets.contains(candidate.getUUID())
                        && hint.entityTypeIds().contains(
                                BuiltInRegistries.ENTITY_TYPE.getKey(candidate.getType())))) {
            if (!EntitySemanticSafety.matchesRelation(entity, relation)) continue;
            List<String> reasons = EntitySemanticSafety.protectionReasons(
                    player, entity, relation, r.protectedLabels, true);
            if (reasons.isEmpty()) {
                safe.add(entity);
            } else if (protectedCandidates != null) {
                protectedCandidates.add(Map.of(
                        "entity_type",
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                        "protection_evidence", reasons));
            }
        }
        safe.sort(Comparator.comparingDouble(player::distanceToSqr));
        return safe;
    }

    private TaskState tickActiveChild() {
        r.extendDeadlineTo(activeRecord.getDeadlineGameTime());
        int liveCount = count(activeNeed.itemIds);
        if (liveCount > activeLastObservedCount) {
            activeLastObservedCount = liveCount;
            renewProgressLease();
        }
        activeNeed.lastObservedCount = liveCount;
        // The root fact is checked by onTick before this method. Check the active recursive need too:
        // an external pickup or the child's previous effect may have completed it already.
        if (liveCount >= activeNeed.requiredFinalCount
                && !activeChild.mustSettleBeforeSatisfiedCancellation()) {
            Need satisfiedNeed = activeNeed;
            cancelActiveBecauseSatisfied();
            if (!needs.isEmpty() && needs.peek() == satisfiedNeed) {
                Need satisfied = needs.pop();
                propagateSatisfiedNeed(satisfied);
            }
            return TaskState.RUNNING;
        }
        TaskState authorizationStop = revalidateActiveHuntAuthorization();
        if (authorizationStop != null) return authorizationStop;
        TaskState harmlessRetarget = retargetUncommittedHuntToCloserCandidate();
        if (harmlessRetarget != null) return harmlessRetarget;
        TaskState terminal;
        if (player.level().getGameTime() >= activeRecord.getDeadlineGameTime()) {
            activeChild.stop(player, Task.StopReason.REPLACED);
            terminal = TaskState.TIMEOUT;
        } else {
            terminal = runChild(activeChild);
            r.extendDeadlineTo(activeRecord.getDeadlineGameTime());
            if (terminal == null) return TaskState.RUNNING;
        }
        TaskResult result = activeChild.result(terminal);
        int after = count(activeNeed.itemIds);
        int progress = Math.max(0, after - activeBeforeCount);
        markActiveEffectsIfObserved(terminal, result, progress);
        if (progress > 0) renewProgressLease();
        activeNeed.lastObservedCount = after;
        recordAttempt(terminal, result, after, progress, false);

        Need completedNeed = activeNeed;
        SemanticAcquireTaskRecord.Source completedSource = activeSource;
        TaskRecord completedRecord = activeRecord;
        HuntChildStage completedHuntStage = activeHuntStage;
        UUID completedHuntTarget = activeHuntTarget;
        clearActive();

        // Defeating the source entity is not the whole harmful acquisition transaction.  The
        // attack child also owns every attributable, reachable drop from that kill.  If that
        // settlement failed, requested-item progress must not launder the incomplete loot sweep
        // into semantic success (for example: wool entered inventory while mutton remained).
        if (completedSource == SemanticAcquireTaskRecord.Source.HUNT
                && completedHuntStage == HuntChildStage.ATTACK
                && terminal != TaskState.SUCCESS
                && huntDefeated(result)) {
            addIssue("hunt", "hunt_loot_settlement_incomplete",
                    "the target was defeated, but collection of all attributable reachable "
                            + "drops did not settle; requested-item progress is retained but the "
                            + "acquisition is not reported as complete",
                    Map.of("terminal_state", terminal.name().toLowerCase(),
                            "child_message", result == null || result.message() == null
                                    ? "" : result.message(),
                            "inventory_progress", progress,
                            "requires_narration", true,
                            "loot_receipt", result == null || result.data() == null
                                    ? Map.of() : result.data()));
            failureNeed = completedNeed;
            return failAcquisition(
                    "hunt_loot_settlement_incomplete",
                    "a defeated source left attributable reachable loot unsettled; stopped before "
                            + "pretending the acquisition transaction was complete",
                    childFailureType(terminal, result));
        }

        if (count(r.itemIds) >= r.count) return TaskState.SUCCESS;
        if (after >= completedNeed.requiredFinalCount) {
            if (!needs.isEmpty() && needs.peek() == completedNeed) {
                Need satisfied = needs.pop();
                propagateSatisfiedNeed(satisfied);
            }
            renewProgressLease();
            return TaskState.RUNNING;
        }
        if (completedSource == SemanticAcquireTaskRecord.Source.HUNT) {
            return finishHuntChild(
                    completedNeed, completedRecord, completedHuntStage, completedHuntTarget,
                    terminal, result, progress);
        }
        if (completedSource == SemanticAcquireTaskRecord.Source.MINE
                && result != null && result.data() != null
                && "wrong_tool".equals(result.data().get("failure_type"))) {
            if (completedRecord instanceof MineBlockTaskRecord mine && mine.requireEfficientTool) {
                completedNeed.miningToolPrerequisitePushed = false;
                completedNeed.plannedSourceOrder = null;
                completedNeed.exhaustedSources.remove(SemanticAcquireTaskRecord.Source.MINE);
                addIssue("mine", "work_tool_exhausted", "settled the batch's drops; prepare a replacement work tool",
                        Map.of("gathered", progress));
                return TaskState.RUNNING;
            }
            addIssue("mine", "wrong_tool",
                    "the mining child rejected the current harvesting tools; the next source "
                            + "decision is based on structured failure_type evidence",
                    Map.of("failure_type", "wrong_tool"));
            advanceSource(completedNeed);
            return TaskState.RUNNING;
        }
        if (completedSource == SemanticAcquireTaskRecord.Source.STORAGE
                && result != null && result.data() != null
                && (bool(result.data().get("outcome_uncertain"))
                        || "uncertain".equals(result.data().get("status")))) {
            outcomeUncertain = true;
            addIssue("storage", "storage_effect_uncertain",
                    "the storage child reported an uncertain effect; blind retry is forbidden",
                    result.data());
            return failAcquisition(
                    "storage_effect_uncertain",
                    "storage may already have changed state, but the final inventory fact is still false; "
                            + "stop and review before retrying",
                    FailureType.UNKNOWN);
        }

        if (progress == 0 || terminal != TaskState.SUCCESS) {
            addIssue(completedSource.name().toLowerCase(), "child_task_incomplete",
                    result == null ? "child task ended without a result" : result.message(),
                    result == null || result.data() == null ? Map.of() : result.data());
        }
        boolean structuredFailure = structuredFailure(terminal, result);
        switch (completedSource) {
            case NEARBY -> {
                if (progress == 0 || structuredFailure) advanceSource(completedNeed);
            }
            case CRAFT -> {
                if (progress == 0 || structuredFailure) {
                    if (completedRecord instanceof CraftTaskRecord craft) {
                        if (recoverCraftingSurface(completedNeed, craft, result)) {
                            return TaskState.RUNNING;
                        }
                        if (completedNeed.committedRecipeIds.contains(
                                craft.recipeId.toString())
                                && completedNeed.committedRecipeEffectsObserved) {
                            addIssue("craft", "committed_recipe_failed_after_effects",
                                    "the selected recipe failed after inventory or world effects were observed; "
                                            + "automatic recipe switching was stopped",
                                    Map.of("recipe_id", craft.recipeId.toString(),
                                            "requires_narration", true,
                                            "partial_effects", true));
                            return failAcquisition(
                                    "committed_recipe_failed_after_effects",
                                    "the committed crafting route changed inventory or the world before it failed; "
                                            + "review the receipt before choosing another route",
                                    FailureType.NO_MATERIAL);
                        }
                        // Retry the semantic craft source, but never the same proven-failed recipe.
                        // This allows a 2x2 alternative after every concrete 3x3 station/site route
                        // failed, without blindly repeating the failed physical effect.
                        completedNeed.rejectedRecipes.add(craft.recipeId.toString());
                        completedNeed.committedRecipeIds.remove(craft.recipeId.toString());
                        if (completedNeed.committedRecipeIds.isEmpty()) {
                            completedNeed.committedRecipeEffectsObserved = false;
                        }
                    } else {
                        advanceSource(completedNeed);
                    }
                }
            }
            case COOK -> {
                if (progress == 0 || structuredFailure) {
                    advanceSource(completedNeed);
                }
            }
            case STORAGE, MINE -> {
                if (progress == 0 || structuredFailure) {
                    advanceSource(completedNeed);
                }
            }
            case TRADE -> {
                if (progress == 0 || structuredFailure) {
                    if (completedNeed.preferredTradeOutput != null) {
                        completedNeed.rejectedTradeOutputs.add(
                                completedNeed.preferredTradeOutput);
                        completedNeed.preferredTradeOutput = null;
                    }
                    if (completedNeed.rejectedTradeOutputs.containsAll(
                            completedNeed.itemIds)) {
                        advanceSource(completedNeed);
                    }
                }
            }
            default -> advanceSource(completedNeed);
        }
        return TaskState.RUNNING;
    }

    private TaskState revalidateActiveHuntAuthorization() {
        if (activeSource != SemanticAcquireTaskRecord.Source.HUNT
                || activeHuntStage != HuntChildStage.ATTACK
                || !(activeRecord instanceof AttackTaskRecord attack)
                || attack.entityIds.isEmpty()) {
            return null;
        }
        Entity live = player.clientLevel.getEntity(attack.entityIds.getFirst());
        // Once the exact entity is dead/unloaded, AttackCompanionTask owns attribution and loot
        // settlement. Interrupting that stage here would strand the very drops it must collect.
        if (live == null || live.isRemoved() || !live.isAlive()) return null;

        SemanticAcquireTaskRecord.SourceHint hint = sourceHint(activeNeed);
        GenericEntitySearchTaskRecord.Relation relation =
                SemanticSourceKnowledge.huntRelation(hint.entityTypeIds());
        boolean sameIdentity = activeHuntTarget != null
                && activeHuntTarget.equals(live.getUUID());
        boolean sameType = hint.entityTypeIds().contains(
                BuiltInRegistries.ENTITY_TYPE.getKey(live.getType()));
        boolean sameRelation = EntitySemanticSafety.matchesRelation(live, relation);
        if (!sameIdentity || !sameType || !sameRelation) {
            return stopActiveHuntAfterAuthorizationChange(
                    List.of(), "hunt_target_identity_or_relation_changed", false);
        }

        List<String> protectionReasons = EntitySemanticSafety.protectionReasons(
                player, live, relation, r.protectedLabels, true);
        if (protectionReasons.isEmpty()) return null;
        return stopActiveHuntAfterAuthorizationChange(
                protectionReasons, "hunt_target_became_protected", true);
    }

    /**
     * A search receipt can select the nearest entity at that instant, then a much nearer member of
     * the same population can enter loaded evidence while the body is still approaching. Before
     * the first strike, changing that execution handle is harmless; after a strike, defeat or loot
     * begins, the combat/drop transaction must remain committed to the original identity.
     */
    private TaskState retargetUncommittedHuntToCloserCandidate() {
        if (activeSource != SemanticAcquireTaskRecord.Source.HUNT
                || activeHuntStage != HuntChildStage.ATTACK
                || !(activeRecord instanceof AttackTaskRecord attack)
                || attack.entityIds.isEmpty()
                || attack.strikes() > 0
                || !attack.defeated().isEmpty()
                || activeChild.mustSettleBeforeSatisfiedCancellation()) {
            return null;
        }
        Entity current = player.clientLevel.getEntity(attack.entityIds.getFirst());
        if (current == null || current.isRemoved() || !current.isAlive()) return null;

        SemanticAcquireTaskRecord.SourceHint hint = sourceHint(activeNeed);
        GenericEntitySearchTaskRecord.Relation relation =
                SemanticSourceKnowledge.huntRelation(hint.entityTypeIds());
        ResourceLocation currentType = BuiltInRegistries.ENTITY_TYPE.getKey(current.getType());
        Entity replacement = safeLoadedHuntCandidates(
                        hint,
                        relation,
                        GenericEntitySearchCompanionTask.LOADED_EVIDENCE_RADIUS,
                        null)
                .stream()
                .filter(candidate -> currentType.equals(
                        BuiltInRegistries.ENTITY_TYPE.getKey(candidate.getType())))
                .findFirst()
                .orElse(null);
        if (replacement == null
                || replacement.getUUID().equals(current.getUUID())
                || !materiallyCloserForAttack(current, replacement)) {
            return null;
        }

        // Recheck the barrier immediately before cancellation. No old target is rejected: it
        // remains a valid future candidate; this is only a harmless nearest-target correction.
        if (attack.strikes() > 0
                || !attack.defeated().isEmpty()
                || activeChild.mustSettleBeforeSatisfiedCancellation()) {
            return null;
        }
        Need retargetedNeed = activeNeed;
        double oldDistance = player.distanceTo(current);
        double newDistance = player.distanceTo(replacement);
        activeChild.stop(player, Task.StopReason.REPLACED);
        activeChild.result(TaskState.CANCELLED);
        clearActive();

        harmlessHuntRetargets++;
        org.maiwithu.maicraft.core.Constants.LOG.info(
                "[maicraft-task] harmless pre-strike hunt retarget type={} distance={} -> {}",
                currentType, oldDistance, newDistance);
        long now = player.level().getGameTime();
        AttackTaskRecord redirected = new AttackTaskRecord(
                childId("hunt-attack"), now + HUNT_TICKS,
                List.of(replacement.getId()), false, true);
        return startHuntChild(
                retargetedNeed,
                redirected,
                HuntChildStage.ATTACK,
                replacement.getUUID(),
                "retarget a substantially nearer loaded unprotected " + currentType
                        + " before any combat effect");
    }

    /**
     * Hysteresis is derived from the live vanilla/modded entity interaction reach rather than an
     * arbitrary world-distance constant. Entering current strike reach is categorically better;
     * otherwise the replacement must remove at least one whole strike-reach band of approach.
     */
    private boolean materiallyCloserForAttack(Entity current, Entity replacement) {
        double nativeReach = player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        double currentReach = Swing.reachTo(nativeReach, current.getBbWidth());
        double replacementReach = Swing.reachTo(nativeReach, replacement.getBbWidth());
        double currentDistance = player.distanceTo(current);
        double replacementDistance = player.distanceTo(replacement);
        if (!(replacementDistance < currentDistance)) return false;

        double currentApproach = Math.max(0.0, currentDistance - currentReach);
        double replacementApproach = Math.max(0.0, replacementDistance - replacementReach);
        if (replacementApproach == 0.0 && currentApproach > 0.0) return true;
        double reachBand = Math.max(currentReach, replacementReach);
        return replacementApproach + reachBand <= currentApproach;
    }

    private TaskState stopActiveHuntAfterAuthorizationChange(
            List<String> protectionReasons, String issueCode, boolean requiresDecision) {
        activeChild.stop(player, Task.StopReason.REPLACED);
        TaskResult stopped = activeChild.result(TaskState.CANCELLED);
        int after = count(activeNeed.itemIds);
        int progress = Math.max(0, after - activeBeforeCount);
        markActiveEffectsIfObserved(TaskState.CANCELLED, stopped, progress);
        recordAttempt(TaskState.CANCELLED, stopped, after, progress, false);

        Need interruptedNeed = activeNeed;
        UUID interruptedTarget = activeHuntTarget;
        clearActive();
        if (interruptedTarget != null) rejectedHuntTargets.add(interruptedTarget);
        interruptedNeed.huntSearchExpandedView = true;

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("protection_evidence", List.copyOf(protectionReasons));
        facts.put("inventory_progress", progress);
        facts.put("requires_narration", requiresDecision);
        facts.put("child_receipt", stopped == null || stopped.data() == null
                ? Map.of() : stopped.data());
        addIssue("hunt", issueCode,
                requiresDecision
                        ? "the exact active target gained explicit protection evidence; the attack was stopped"
                        : "the active runtime target no longer matched its retained identity, type or relation",
                facts);
        if (requiresDecision) interruptedNeed.decisionRequired = true;
        renewProgressLease();
        return TaskState.RUNNING;
    }

    private TaskState finishHuntChild(
            Need need,
            TaskRecord completedRecord,
            HuntChildStage stage,
            UUID targetUuid,
            TaskState terminal,
            TaskResult result,
            int progress) {
        if (stage == HuntChildStage.SEARCH) {
            if (terminal == TaskState.SUCCESS
                    && result != null && result.success()
                    && bool(result.data() == null ? null : result.data().get("verified"))) {
                Entity retained = completedRecord instanceof GenericEntitySearchTaskRecord search
                        ? revalidateInternalHuntTarget(need, search) : null;
                if (retained != null) {
                    if (takePlannerStep()) {
                        need.attempted(SemanticAcquireTaskRecord.Source.HUNT);
                        long now = player.level().getGameTime();
                        AttackTaskRecord attack = new AttackTaskRecord(
                                childId("hunt-attack"), now + HUNT_TICKS,
                                List.of(retained.getId()), false, true);
                        return startHuntChild(
                                need, attack, HuntChildStage.ATTACK, retained.getUUID(),
                                "hunt the still-live entity verified by the internal search receipt");
                    }
                }
                // If the exact identity crossed a load boundary during child settlement, preserve
                // the old broad re-observation fallback. No runtime identity enters public data.
                need.huntSearchExpandedView = true;
                renewProgressLease();
                return TaskState.RUNNING;
            }
            need.huntSearchExpandedView = false;
            Map<String, Object> searchReceipt = result == null || result.data() == null
                    ? Map.of() : result.data();
            boolean searchRequiresDecision = bool(searchReceipt.get("requires_decision"))
                    || "only_protected_or_ambiguous_entity_evidence".equals(
                            searchReceipt.get("failure_code"));
            Map<String, Object> searchFacts = new LinkedHashMap<>();
            searchFacts.put("terminal_state", terminal.name().toLowerCase());
            searchFacts.put("search_attempts", need.huntSearchAttempts);
            searchFacts.put("requires_narration", true);
            searchFacts.put("search_receipt", searchReceipt);
            addIssue("hunt", "hunt_entity_search_incomplete",
                    "first-person search ended without a complete acceptable entity observation",
                    searchFacts);
            if (searchRequiresDecision) {
                need.decisionRequired = true;
                return TaskState.RUNNING;
            }
            advanceSource(need);
            return TaskState.RUNNING;
        }

        if (stage == HuntChildStage.ATTACK) {
            if (terminal == TaskState.SUCCESS || huntDefeated(result)) {
                if (progress == 0) {
                    if (targetUuid != null) rejectedHuntTargets.add(targetUuid);
                    addIssue("hunt", "expected_hunt_drop_not_observed",
                            "the authorized target was defeated and its own new-drop sweep finished, "
                                    + "but the requested live inventory fact did not increase",
                            Map.of("inventory_progress", 0,
                                    "expected_item_ids", stringIds(
                                            sourceHint(need).expectedItemIds()),
                                    "loot_gained", result == null || result.data() == null
                                            ? Map.of()
                                            : result.data().getOrDefault("loot_gained", Map.of()),
                                    "unreachable_drop_count", result == null || result.data() == null
                                            ? 0
                                            : result.data().getOrDefault("unreachable_drop_count", 0)));
                    need.huntSearchExpandedView = true;
                    renewProgressLease();
                } else {
                    renewProgressLease();
                }
                // AttackCompanionTask already snapshots pre-existing drops, tracks only new/merged
                // loot from this kill, approaches it and reports the resulting inventory delta.
                // A second type-wide collector here would be able to steal old or unrelated items.
                return TaskState.RUNNING;
            }
            if (targetUuid != null) rejectedHuntTargets.add(targetUuid);
            addIssue("hunt", "hunt_target_lost_or_unreachable",
                    "the selected loaded target disappeared or could not be reached; "
                            + "a later authorized attempt would re-observe loaded entities",
                    Map.of("terminal_state", terminal.name().toLowerCase(),
                            "child_message", result == null || result.message() == null
                                    ? "" : result.message(),
                            "inventory_progress", progress,
                            "attempted_targets", need.attempts(
                                    SemanticAcquireTaskRecord.Source.HUNT)));
            need.huntSearchExpandedView = true;
            renewProgressLease();
            return TaskState.RUNNING;
        }

        addIssue("hunt", "hunt_child_stage_missing",
                "a hunt child finished without a retained search/attack stage",
                Map.of("terminal_state", terminal.name().toLowerCase()));
        advanceSource(need);
        return TaskState.RUNNING;
    }

    private Entity revalidateInternalHuntTarget(
            Need need, GenericEntitySearchTaskRecord search) {
        Set<UUID> retained = new LinkedHashSet<>(search.internalVerifiedEntityUuids());
        if (retained.isEmpty()) return null;
        SemanticAcquireTaskRecord.SourceHint hint = sourceHint(need);
        GenericEntitySearchTaskRecord.Relation relation =
                SemanticSourceKnowledge.huntRelation(hint.entityTypeIds());
        AABB box = player.getBoundingBox().inflate(
                GenericEntitySearchCompanionTask.LOADED_EVIDENCE_RADIUS);
        return player.clientLevel.getEntities(player, box, candidate ->
                        candidate != player
                                && retained.contains(candidate.getUUID())
                                && !rejectedHuntTargets.contains(candidate.getUUID())
                                && !candidate.isRemoved()
                                && candidate.isAlive()
                                && candidate.isAttackable()
                                && hint.entityTypeIds().contains(
                                        BuiltInRegistries.ENTITY_TYPE.getKey(candidate.getType())))
                .stream()
                .filter(entity -> EntitySemanticSafety.matchesRelation(entity, relation))
                .filter(entity -> EntitySemanticSafety.protectionReasons(
                                player, entity, relation, r.protectedLabels, true)
                        .isEmpty())
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
    }

    private TaskState startChild(
            Need need,
            SemanticAcquireTaskRecord.Source source,
            TaskRecord record,
            String detail) {
        activeNeed = need;
        activeSource = source;
        activeRecord = record;
        activeChild = TaskFactory.create(player, record);
        activeDetail = detail;
        activeBeforeCount = count(need.itemIds);
        activeLastObservedCount = activeBeforeCount;
        activeBeforeInventory = inventorySnapshot();
        r.extendDeadlineTo(record.getDeadlineGameTime());
        return TaskState.RUNNING;
    }

    private TaskState startHuntChild(
            Need need,
            TaskRecord record,
            HuntChildStage stage,
            UUID targetUuid,
            String detail) {
        TaskState state = startChild(
                need, SemanticAcquireTaskRecord.Source.HUNT, record, detail);
        activeHuntStage = stage;
        activeHuntTarget = targetUuid;
        return state;
    }

    private void cancelActiveBecauseSatisfied() {
        if (activeChild == null) return;
        activeChild.stop(player, Task.StopReason.REPLACED);
        TaskResult result = activeChild.result(TaskState.CANCELLED);
        int after = count(activeNeed.itemIds);
        int progress = Math.max(0, after - activeBeforeCount);
        markActiveEffectsIfObserved(TaskState.CANCELLED, result, progress);
        recordAttempt(TaskState.CANCELLED, result, after, progress, true);
        clearActive();
    }

    private void clearActive() {
        activeChild = null;
        activeRecord = null;
        activeNeed = null;
        activeSource = null;
        activeDetail = null;
        activeBeforeCount = 0;
        activeLastObservedCount = 0;
        activeBeforeInventory = Map.of();
        activeHuntStage = HuntChildStage.NONE;
        activeHuntTarget = null;
    }

    /**
     * Route commitment is based on observed effects, never on mere dispatch. A failed search or
     * menu-open attempt may be replaced safely; an inventory delta, a placed station, mining,
     * crafting, transfer or attack receipt must be narrated before another route is chosen.
     */
    private void markActiveEffectsIfObserved(
            TaskState terminal, TaskResult result, int targetProgress) {
        if (activeNeed == null || !activeChildEffectsObserved(
                terminal, result, targetProgress)) return;
        activeNeed.effectsObserved = true;
        if (activeRecord instanceof CraftTaskRecord craft
                && activeNeed.committedRecipeIds.contains(craft.recipeId.toString())) {
            activeNeed.committedRecipeEffectsObserved = true;
        }
    }

    private boolean activeChildEffectsObserved(
            TaskState terminal, TaskResult result, int targetProgress) {
        if (targetProgress > 0 || !activeBeforeInventory.equals(inventorySnapshot())) return true;
        Map<String, Object> data = result == null || result.data() == null
                ? Map.of() : result.data();
        for (String key : List.of(
                "effects_started", "outcome_uncertain", "crafting_table_placed",
                "station_placed")) {
            if (bool(data.get(key))) return true;
        }
        for (String key : List.of(
                "crafted", "gathered", "collected", "mined", "broken", "placed",
                "strikes", "defeated_targets", "inventory_progress", "withdrawn",
                "deposited", "transferred")) {
            if (positiveNumber(data.get(key))) return true;
        }
        // A terminal attack can have damaged a living target without producing loot yet. Treat
        // that physical authorization as consequential even if an older receipt lacks counters.
        return activeSource == SemanticAcquireTaskRecord.Source.HUNT
                && activeHuntStage == HuntChildStage.ATTACK
                && terminal != TaskState.PENDING;
    }

    private Map<ResourceLocation, Integer> inventorySnapshot() {
        Map<ResourceLocation, Integer> snapshot = new LinkedHashMap<>();
        int slots = Math.min(
                PlayerInv.BUILDABLE_SLOTS, player.getInventory().getContainerSize());
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            snapshot.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()),
                    stack.getCount(), Integer::sum);
        }
        return Map.copyOf(snapshot);
    }

    private static boolean huntDefeated(TaskResult result) {
        return result != null && result.data() != null
                && positiveNumber(result.data().get("defeated_targets"));
    }

    private static FailureType childFailureType(TaskState terminal, TaskResult result) {
        if (result != null && result.data() != null) {
            Object raw = result.data().get("failure_type");
            if (raw != null) {
                try {
                    return FailureType.valueOf(
                            String.valueOf(raw).toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // Fall through to the lifecycle-derived reason.
                }
            }
        }
        return switch (terminal) {
            case TIMEOUT -> FailureType.TIMED_OUT;
            case CANCELLED -> FailureType.INTERRUPTED;
            default -> FailureType.UNKNOWN;
        };
    }

    private boolean structuredFailure(TaskState terminal, TaskResult result) {
        if (terminal == TaskState.SUCCESS && result != null && result.success()) return false;
        if (result != null && result.data() != null) {
            Map<String, Object> data = result.data();
            if (data.containsKey("failure_type") || data.containsKey("failure_code")
                    || bool(data.get("requires_decision"))
                    || bool(data.get("outcome_uncertain"))) {
                return true;
            }
        }
        return terminal == TaskState.FAILED;
    }

    private String huntSearchState(
            Need need,
            SemanticAcquireTaskRecord.SourceHint hint,
            GenericEntitySearchTaskRecord.Relation relation) {
        BlockPos position = player.blockPosition();
        return player.level().dimension().location()
                + "|" + (position.getX() >> 4) + ',' + (position.getZ() >> 4)
                + "|" + relation.name()
                + "|" + String.join(",", stringIds(hint.entityTypeIds()))
                + "|expanded=" + need.huntSearchExpandedView
                // A successful kill or a newly rejected identity changes the search facts even
                // inside the same chunk. Omitting them made the repeated-state guard suppress the
                // next required hunt after partial item progress.
                + "|inventory=" + count(need.itemIds)
                + "|rejected=" + rejectedHuntTargets.size();
    }

    private static boolean positiveNumber(Object value) {
        return value instanceof Number number && number.intValue() > 0;
    }

    private TaskState exhaustNeed(Need need) {
        if (need.depth == 0) {
            DimensionBarrier barrier = preferredDimensionBarrier();
            if (barrier != null) {
                failureDimension = barrier;
                return failAcquisition(
                        "requires_dimension",
                        "a known physical source is valid only in another dimension; no movement "
                                + "or attack was started for that source",
                        FailureType.NO_MATERIAL);
            }
            return failAcquisition(
                    "allowed_sources_exhausted",
                    "none of the allowed source families could make the final inventory fact true",
                    FailureType.NO_MATERIAL);
        }
        if (!needs.isEmpty() && needs.peek() == need) needs.pop();
        Need parent = needs.peek();
        if (parent == null) {
            return failAcquisition(
                    "recursive_need_parent_missing",
                    "a recursive recipe need ended without its parent",
                    FailureType.INTERNAL);
        }
        if (need.stockOnlyTool && !need.decisionRequired) {
            parent.preferredToolTierCap = 1;
            parent.miningToolPrerequisitePushed = false;
            parent.effectsObserved |= need.effectsObserved;
            addIssue("mine", "tool_upgrade_stock_unavailable",
                    "the upgrade could not be supplied from existing stock; prepare the ordinary work tool instead",
                    Map.of("attempted_tool_ids", itemStrings(need.itemIds)));
            return TaskState.RUNNING;
        }
        if (need.decisionRequired) {
            addIssue("planner", "prerequisite_decision_required",
                    "a recursive prerequisite exhausted every non-decision source and reached a "
                            + "player-facing permission or protection boundary",
                    Map.of("ingredient_item_ids", itemStrings(need.itemIds),
                            "requires_narration", true,
                            "partial_effects", need.effectsObserved));
            failureNeed = need;
            return failAcquisition(
                    "prerequisite_decision_required",
                    "a prerequisite now needs a narrated player decision; MaiCraft stopped instead "
                            + "of silently changing the recipe",
                    FailureType.NO_MATERIAL);
        }
        boolean committedFrontier = need.parentRecipeIds.stream()
                .anyMatch(parent.committedRecipeIds::contains);
        if (committedFrontier
                && (need.effectsObserved || parent.committedRecipeEffectsObserved)) {
            addIssue("craft", "committed_prerequisite_unmet",
                    "the committed recipe's prerequisite exhausted allowed sources after child "
                            + "work began; automatic recipe switching was stopped",
                    Map.of("recipe_ids", List.copyOf(need.parentRecipeIds),
                            "ingredient_item_ids", itemStrings(need.itemIds),
                            "partial_effects", true,
                            "requires_narration", true));
            failureNeed = need;
            return failAcquisition(
                    "committed_prerequisite_unmet",
                    "a committed recipe prerequisite could not be completed after work began; "
                            + "review the partial effects and choose a recovery before trying another route",
                    FailureType.NO_MATERIAL);
        }
        if (committedFrontier) {
            parent.committedRecipeIds.clear();
            parent.committedRecipeEffectsObserved = false;
        }
        parent.effectsObserved |= need.effectsObserved;
        parent.rejectedRecipes.addAll(need.parentRecipeIds);
        List<String> parentRecipeIds = need.parentRecipeIds.isEmpty()
                ? List.of("unknown") : List.copyOf(need.parentRecipeIds);
        addIssue("craft", "recursive_ingredient_unmet",
                "one recipe frontier was abandoned after its ingredient alternatives exhausted allowed sources",
                Map.of("recipe_id", parentRecipeIds.getFirst(),
                        "recipe_ids", parentRecipeIds,
                        "ingredient_item_ids", itemStrings(need.itemIds),
                        "required_final_count", need.requiredFinalCount,
                        "observed_final_count", count(need.itemIds)));
        return TaskState.RUNNING;
    }

    private void propagateSatisfiedNeed(Need satisfied) {
        if (needs.isEmpty()) return;
        Need parent = needs.peek();
        parent.effectsObserved |= satisfied.effectsObserved;
        if (satisfied.toolPrerequisite) parent.miningToolPrerequisitePushed = false;
        if (satisfied.effectsObserved && satisfied.parentRecipeIds.stream()
                .anyMatch(parent.committedRecipeIds::contains)) {
            parent.committedRecipeEffectsObserved = true;
        }
    }

    private void collectCraftCandidates(
            ResourceLocation output, CraftOps.Plan plan, List<CraftCandidate> target) {
        List<?> values = plan.recoveryCandidates();
        if (values.isEmpty()) {
            TaskResult immediate = plan.immediate();
            if (immediate == null || immediate.data() == null) return;
            Object raw = immediate.data().get("candidate_recipes");
            if (!(raw instanceof List<?> fallback)) return;
            values = fallback;
        }
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) continue;
            Map<String, Object> data = stringKeyMap(map);
            String recipeId = string(data.get("recipe_id"));
            if (recipeId == null) continue;
            boolean surfaceSupported = bool(data.get("crafting_surface_supported"));
            boolean surfaceReady = bool(data.get("crafting_surface_ready"));
            CraftPlanCost.Surface surface = craftSurface(
                    data.get("crafting_surface_state"), surfaceSupported, surfaceReady,
                    bool(data.get("crafting_surface_preparable")));
            CraftPlanCost cost = new CraftPlanCost(
                    Math.max(0, integer(data.get("ingredients_missing"), Integer.MAX_VALUE)),
                    surface,
                    Math.max(0, integer(data.get("output_waste"), 0)),
                    Math.max(0, integer(data.get("ingredient_uses"), Integer.MAX_VALUE)),
                    output + "|" + recipeId);
            List<ResourceLocation> surfacePrerequisites = new ArrayList<>();
            Object rawPrerequisites = data.get("crafting_surface_prerequisite_item_ids");
            if (rawPrerequisites instanceof List<?> ids) {
                for (Object rawId : ids) {
                    ResourceLocation id = ResourceLocation.tryParse(String.valueOf(rawId));
                    if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                        surfacePrerequisites.add(id);
                    }
                }
            }
            if (surfacePrerequisites.isEmpty()) {
                String fallback = string(data.get("crafting_surface_prerequisite_item_id"));
                ResourceLocation id = fallback == null ? null : ResourceLocation.tryParse(fallback);
                if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                    surfacePrerequisites.add(id);
                }
            }
            target.add(new CraftCandidate(
                    output,
                    recipeId,
                    data,
                    cost,
                    surfaceSupported,
                    surfaceReady,
                    List.copyOf(new LinkedHashSet<>(surfacePrerequisites))));
        }
    }

    /**
     * Insert one finite-lineage, Mod-owned workstation item prerequisite ahead of an unchanged recipe.
     * The prerequisite inherits exactly the source families already authorized for its parent;
     * adding a workstation must not silently narrow the user's original acquisition policy.
     */
    private boolean pushCraftingSurfacePrerequisite(
            Need parent, CraftCandidate blockedRecipe) {
        return pushCraftingSurfacePrerequisite(
                parent, blockedRecipe.recipeId(), blockedRecipe.outputItem().toString(),
                blockedRecipe.surfacePrerequisiteItems());
    }

    /**
     * A physical craft can discover that every route to an observed station is unusable only after
     * execution. Insert the same semantic workstation need used by planning, then leave the parent
     * recipe untouched so it resumes after the carried surface exists.
     */
    private boolean recoverCraftingSurface(
            Need parent, CraftTaskRecord craft, TaskResult result) {
        if (result == null || result.data() == null) return false;
        String code = string(result.data().get("failure_code"));
        if (code == null || !code.startsWith("crafting_surface_")) return false;

        List<ResourceLocation> itemIds = new ArrayList<>();
        Object raw = result.data().get("crafting_surface_prerequisite_item_ids");
        if (raw instanceof List<?> values) {
            for (Object value : values) {
                ResourceLocation id = ResourceLocation.tryParse(String.valueOf(value));
                if (id != null && BuiltInRegistries.ITEM.containsKey(id)) itemIds.add(id);
            }
        }
        if (itemIds.isEmpty()) {
            itemIds.addAll(CraftingWorkstationCoordinator.prerequisiteItemIds());
        }
        // If a table is still carried, another item cannot repair a placement-site failure.
        if (count(itemIds) > 0) return false;
        return pushCraftingSurfacePrerequisite(
                parent, craft.recipeId.toString(), parent.itemIds.getFirst().toString(), itemIds);
    }

    private boolean pushCraftingSurfacePrerequisite(
            Need parent, String blockedRecipeId, String outputItemId,
            List<ResourceLocation> candidates) {
        List<ResourceLocation> itemIds = candidates.stream()
                .filter(id -> !parent.lineageItems.contains(id))
                .toList();
        if (itemIds.isEmpty() || blockedRecipeId == null || blockedRecipeId.isBlank()) return false;

        Set<ResourceLocation> lineageItems = new LinkedHashSet<>(parent.lineageItems);
        lineageItems.addAll(itemIds);
        Set<String> lineageRecipes = new LinkedHashSet<>(parent.lineageRecipes);
        lineageRecipes.add(blockedRecipeId);
        // This prerequisite is still part of the user's original acquisition. Preserve every
        // source family the parent explicitly authorized instead of inventing a narrower gate.
        List<SemanticAcquireTaskRecord.Source> prerequisiteSources =
                List.copyOf(parent.allowedSources);
        if (prerequisiteSources.isEmpty()) return false;
        if (!parent.committedRecipeIds.isEmpty()
                && !parent.committedRecipeIds.contains(blockedRecipeId)) return false;
        if (!parent.surfaceRecoveryRecipes.add(blockedRecipeId)) return false;
        commitRecipe(parent, Set.of(blockedRecipeId));
        Need prerequisite = new Need(
                itemIds, count(itemIds) + 1, parent.depth + 1,
                lineageItems, lineageRecipes, Set.of(blockedRecipeId),
                prerequisiteSources);
        prerequisite.lastObservedCount = count(prerequisite.itemIds);
        recipeTrace.add(Map.of(
                "recipe_id", blockedRecipeId,
                "output_item_id", outputItemId,
                "depth", parent.depth,
                "internal_prerequisite", "crafting_surface",
                "prerequisite_item_ids", itemStrings(itemIds),
                "allowed_sources", prerequisiteSources.stream()
                        .map(source -> source.name().toLowerCase(java.util.Locale.ROOT))
                        .toList()));
        needs.push(prerequisite);
        renewProgressLease();
        return true;
    }

    private boolean recipeAllowedByCommit(Need need, TaskRecord record) {
        if (need.committedRecipeIds.isEmpty()) return true;
        return record instanceof CraftTaskRecord craft
                && need.committedRecipeIds.contains(craft.recipeId.toString());
    }

    private static boolean recipeAllowedByCommit(Need need, String recipeId) {
        return need.committedRecipeIds.isEmpty()
                || need.committedRecipeIds.contains(recipeId);
    }

    private static void commitRecipe(Need need, Set<String> recipeIds) {
        if (need.committedRecipeIds.isEmpty()) {
            need.committedRecipeIds.addAll(recipeIds);
            need.committedRecipeEffectsObserved = false;
        }
    }

    /** Exclude competing identities so CraftOps can expose the already selected recipe itself. */
    private Set<String> nonCommittedCraftRecipes(Need need) {
        if (need.committedRecipeIds.isEmpty()) return Set.of();
        Set<ResourceLocation> outputs = Set.copyOf(need.itemIds);
        Set<String> excluded = new LinkedHashSet<>();
        for (var holder : ClientRuntime.requireContext(player)
                .connection().getRecipeManager().getRecipes()) {
            try {
                if (!(holder.value() instanceof CraftingRecipe recipe)) continue;
                ItemStack output = RecipeProbe.resultOf(
                        recipe, player.level().registryAccess());
                if (output.isEmpty()
                        || !outputs.contains(BuiltInRegistries.ITEM.getKey(output.getItem()))) {
                    continue;
                }
                String recipeId = holder.id().toString();
                if (!need.committedRecipeIds.contains(recipeId)) excluded.add(recipeId);
            } catch (RuntimeException unusableRecipe) {
                org.maiwithu.maicraft.core.Constants.LOG.debug(
                        "[maicraft-acquire] skipped unusable committed-recipe probe {}: {}",
                        holder.id(), unusableRecipe.toString());
            }
        }
        return Set.copyOf(excluded);
    }

    /**
     * Side-effect-free structural lookahead used only as a tie-break between missing-material
     * candidates. It is not a claim that world resources exist; it measures how many ordinary
     * crafting layers stand between each missing group and a non-crafting leaf. Thus direct wool
     * is preferred to wool-plus-dye conversion without encoding any particular item or recipe.
     */
    private int recursiveCandidateStructureCost(CraftCandidate candidate, Need parent) {
        Object raw = candidate.data().get("ingredients");
        if (!(raw instanceof List<?> values)) return UNREACHABLE_STRUCTURE_COST;
        Map<StructureKey, Integer> memo = new HashMap<>();
        int total = 0;
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) continue;
            Map<String, Object> fact = stringKeyMap(map);
            int missing = integer(fact.get("missing"), 0);
            if (missing <= 0) continue;
            int best = UNREACHABLE_STRUCTURE_COST;
            Object acceptable = fact.get("acceptable_item_ids");
            if (acceptable instanceof List<?> ids) {
                for (Object rawId : ids) {
                    ResourceLocation id = ResourceLocation.tryParse(String.valueOf(rawId));
                    if (id == null || !BuiltInRegistries.ITEM.containsKey(id)
                            || parent.lineageItems.contains(id)) continue;
                    best = Math.min(best, recursiveCraftDepth(
                            id, parent.lineageItems, new LinkedHashSet<>(), memo,
                            STRUCTURAL_RECIPE_DEPTH));
                }
            }
            if (best >= UNREACHABLE_STRUCTURE_COST) return UNREACHABLE_STRUCTURE_COST;
            total = Math.min(UNREACHABLE_STRUCTURE_COST,
                    total + missing * Math.max(1, best + 1));
        }
        return total;
    }

    private int recursiveCraftDepth(
            ResourceLocation itemId,
            Set<ResourceLocation> forbidden,
            Set<ResourceLocation> visiting,
            Map<StructureKey, Integer> memo,
            int remainingDepth) {
        if (forbidden.contains(itemId) || visiting.contains(itemId)) {
            return UNREACHABLE_STRUCTURE_COST;
        }
        Item item = BuiltInRegistries.ITEM.get(itemId);
        if (PlayerInv.buildableCount(player.getInventory(), item) > 0) return 0;
        List<CraftingRecipe> recipes = structuralCraftRecipes().getOrDefault(
                itemId, List.of());
        if (recipes.isEmpty()) return 0;
        if (remainingDepth <= 0) return UNREACHABLE_STRUCTURE_COST;
        Set<ResourceLocation> blocked = new LinkedHashSet<>(forbidden);
        blocked.addAll(visiting);
        StructureKey key = new StructureKey(itemId, remainingDepth, blocked);
        Integer remembered = memo.get(key);
        if (remembered != null) return remembered;

        visiting.add(itemId);
        int bestRecipe = UNREACHABLE_STRUCTURE_COST;
        for (CraftingRecipe recipe : recipes) {
            int criticalDepth = 0;
            boolean valid = true;
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient == null || ingredient.isEmpty()) continue;
                int ingredientDepth = UNREACHABLE_STRUCTURE_COST;
                for (ItemStack accepted : ingredient.getItems()) {
                    if (accepted == null || accepted.isEmpty()) continue;
                    ResourceLocation acceptedId = BuiltInRegistries.ITEM.getKey(
                            accepted.getItem());
                    ingredientDepth = Math.min(ingredientDepth, recursiveCraftDepth(
                            acceptedId, forbidden, visiting, memo, remainingDepth - 1));
                }
                if (ingredientDepth >= UNREACHABLE_STRUCTURE_COST) {
                    valid = false;
                    break;
                }
                criticalDepth = Math.max(criticalDepth, ingredientDepth);
            }
            if (valid) bestRecipe = Math.min(bestRecipe, 1 + criticalDepth);
        }
        visiting.remove(itemId);
        // A real leaf has no crafting recipes. Recipes that all re-enter the lineage are a cycle,
        // not a magically free source.
        memo.put(key, bestRecipe);
        return bestRecipe;
    }

    private Map<ResourceLocation, List<CraftingRecipe>> structuralCraftRecipes() {
        if (structuralCraftRecipes != null) return structuralCraftRecipes;
        Map<ResourceLocation, List<CraftingRecipe>> indexed = new LinkedHashMap<>();
        for (var holder : ClientRuntime.requireContext(player)
                .connection().getRecipeManager().getRecipes()) {
            if (!(holder.value() instanceof CraftingRecipe recipe)) continue;
            if (!RecipeProbe.usableIngredients(recipe)) continue;
            ItemStack output = RecipeProbe.resultOf(
                    recipe, player.level().registryAccess());
            if (output.isEmpty()) continue;
            ResourceLocation outputId = BuiltInRegistries.ITEM.getKey(output.getItem());
            indexed.computeIfAbsent(outputId, ignored -> new ArrayList<>()).add(recipe);
        }
        Map<ResourceLocation, List<CraftingRecipe>> frozen = new LinkedHashMap<>();
        indexed.forEach((id, recipes) -> frozen.put(id, List.copyOf(recipes)));
        structuralCraftRecipes = Map.copyOf(frozen);
        return structuralCraftRecipes;
    }

    /**
     * Build the next material frontier without leaking registry order into physical behavior.
     *
     * <p>When several acceptable outputs each have a recipe that is exactly one material short,
     * acquiring one item from any of those recipes makes one complete recipe executable. Their
     * acceptable inputs therefore form one real semantic alternative set. Nearby collection and
     * mining can then choose the closest reachable member of that set instead of trying recipe IDs
     * alphabetically. More complicated frontiers stay on one recipe because mixing partial inputs
     * from unrelated recipes would not prove either recipe satisfiable.</p>
     */
    private CraftFrontier chooseCraftFrontier(
            CraftCandidate chosen,
            List<CraftCandidate> viableCandidates,
            Need parent) {
        IngredientNeed primary = chooseIngredient(chosen, parent);
        if (primary == null) return null;

        LinkedHashSet<ResourceLocation> itemIds = new LinkedHashSet<>(primary.itemIds());
        LinkedHashSet<String> recipeIds = new LinkedHashSet<>();
        LinkedHashSet<ResourceLocation> outputItemIds = new LinkedHashSet<>();
        recipeIds.add(chosen.recipeId());
        outputItemIds.add(chosen.outputItem());

        if (chosen.cost().missingMaterials() == 1 && primary.missing() == 1) {
            for (CraftCandidate candidate : viableCandidates) {
                if (candidate == chosen
                        || candidate.cost().missingMaterials() != 1
                        || candidate.cost().surface() != chosen.cost().surface()) {
                    continue;
                }
                IngredientNeed alternative = chooseIngredient(candidate, parent);
                if (alternative == null || alternative.missing() != 1) continue;
                itemIds.addAll(alternative.itemIds());
                recipeIds.add(candidate.recipeId());
                outputItemIds.add(candidate.outputItem());
            }
        }

        IngredientNeed frontier = primary;
        if (recipeIds.size() > 1) {
            frontier = new IngredientNeed(
                    List.copyOf(itemIds),
                    1,
                    Map.of(
                            "kind", "one_unit_recipe_alternatives",
                            "alternative_recipe_count", recipeIds.size(),
                            "acceptable_item_count", itemIds.size()));
        }
        return new CraftFrontier(
                frontier,
                java.util.Collections.unmodifiableSet(recipeIds),
                List.copyOf(outputItemIds));
    }

    /**
     * A merged one-unit frontier means every listed item is already a complete way to unlock one
     * parent recipe. Inspect the permitted direct world source before recursively manufacturing
     * whichever member happened to sort first. This is a stable reordering of permissions, not a
     * new permission: inventory, loose-item, storage and direct-mine sources form a stable prefix
     * ahead of craft/cook, while trade/hunt keep their existing side-effect order.
     */
    private static List<SemanticAcquireTaskRecord.Source> prioritizeDirectAlternativeSources(
            List<SemanticAcquireTaskRecord.Source> sources) {
        int craft = sources.indexOf(SemanticAcquireTaskRecord.Source.CRAFT);
        int cook = sources.indexOf(SemanticAcquireTaskRecord.Source.COOK);
        int firstTransformation;
        if (craft < 0) firstTransformation = cook;
        else if (cook < 0) firstTransformation = craft;
        else firstTransformation = Math.min(craft, cook);
        if (firstTransformation < 0) return sources;

        List<SemanticAcquireTaskRecord.Source> ordered = new ArrayList<>(sources);
        List<SemanticAcquireTaskRecord.Source> lateDirect = sources.subList(
                        firstTransformation + 1, sources.size()).stream()
                .filter(SemanticAcquireCompanionTask::isDirectAlternativeSource)
                .toList();
        if (lateDirect.isEmpty()) return sources;
        ordered.removeAll(lateDirect);
        craft = ordered.indexOf(SemanticAcquireTaskRecord.Source.CRAFT);
        cook = ordered.indexOf(SemanticAcquireTaskRecord.Source.COOK);
        if (craft < 0) firstTransformation = cook;
        else if (cook < 0) firstTransformation = craft;
        else firstTransformation = Math.min(craft, cook);
        ordered.addAll(firstTransformation, lateDirect);
        return List.copyOf(ordered);
    }

    private static boolean isDirectAlternativeSource(
            SemanticAcquireTaskRecord.Source source) {
        return source == SemanticAcquireTaskRecord.Source.INVENTORY
                || source == SemanticAcquireTaskRecord.Source.NEARBY
                || source == SemanticAcquireTaskRecord.Source.STORAGE
                || source == SemanticAcquireTaskRecord.Source.MINE;
    }

    /**
     * Collapse repeated recipe slots with the exact same acceptable-item set into one quantity.
     * A three-slot stone-material row is one need for three interchangeable materials, not three
     * separate planner branches.
     */
    private IngredientNeed chooseIngredient(CraftCandidate candidate, Need parent) {
        Object raw = candidate.data().get("ingredients");
        if (!(raw instanceof List<?> values)) return null;
        Map<List<ResourceLocation>, Integer> missingByItems = new LinkedHashMap<>();
        Map<List<ResourceLocation>, List<Map<String, Object>>> factsByItems =
                new LinkedHashMap<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) continue;
            Map<String, Object> fact = stringKeyMap(map);
            int missing = integer(fact.get("missing"), 0);
            if (missing <= 0) continue;
            List<ResourceLocation> declaredIds = new ArrayList<>();
            Object acceptable = fact.get("acceptable_item_ids");
            if (acceptable instanceof List<?> list) {
                for (Object element : list) {
                    ResourceLocation id = ResourceLocation.tryParse(String.valueOf(element));
                    if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                        declaredIds.add(id);
                    }
                }
            }
            declaredIds = declaredIds.stream().distinct().toList();
            boolean truncated = bool(fact.get("acceptable_item_ids_truncated"));
            if (declaredIds.isEmpty()) return null;
            List<ResourceLocation> ids = declaredIds.stream()
                    .filter(id -> !parent.lineageItems.contains(id))
                    .toList();
            // One blocked required group invalidates the entire recipe. Silently dropping it and
            // acquiring a different group first is how "any bed" devolved into making every dye:
            // the recipe still needed an ancestor bed, even though the dye itself was acyclic.
            if (ids.isEmpty()) {
                // A truncated report cannot prove that an unseen alternative is or is not in the
                // lineage. Conservative rejection keeps this recipe side-effect free; it never
                // permits the remaining groups to run as though this required group did not exist.
                if (truncated) return null;
                return null;
            }
            List<ResourceLocation> key = List.copyOf(ids);
            missingByItems.merge(key, missing, Integer::sum);
            factsByItems.computeIfAbsent(key, ignored -> new ArrayList<>()).add(fact);
        }
        List<IngredientNeed> choices = new ArrayList<>();
        for (Map.Entry<List<ResourceLocation>, Integer> entry : missingByItems.entrySet()) {
            Map<String, Object> combinedFact = new LinkedHashMap<>();
            combinedFact.put("missing", entry.getValue());
            combinedFact.put("ingredient_groups",
                    List.copyOf(factsByItems.getOrDefault(entry.getKey(), List.of())));
            choices.add(new IngredientNeed(
                    entry.getKey(), entry.getValue(), Map.copyOf(combinedFact)));
        }
        List<RankedIngredient> ranked = new ArrayList<>();
        for (IngredientNeed choice : choices) {
            ranked.add(new RankedIngredient(
                    choice,
                    ingredientRequiresDecision(choice.itemIds(), parent),
                    recursiveIngredientStructureCost(choice.itemIds(), parent)));
        }
        return ranked.stream()
                // Verify the route's real bottleneck before manufacturing easy accessories. A
                // permission boundary is harder than a transformation; among transformations the
                // deeper frontier is checked first, then the larger material bundle.
                .sorted(Comparator
                        .comparing(RankedIngredient::decisionRequired).reversed()
                        .thenComparing(Comparator.comparingInt(
                                RankedIngredient::structureCost).reversed())
                        .thenComparing(Comparator.comparingInt(
                                (RankedIngredient rankedIngredient) ->
                                        rankedIngredient.ingredient().missing())
                                .reversed())
                        .thenComparing(rankedIngredient -> String.join(",",
                                itemStrings(rankedIngredient.ingredient().itemIds()))))
                .map(RankedIngredient::ingredient)
                .findFirst().orElse(null);
    }

    private boolean ingredientRequiresDecision(
            List<ResourceLocation> itemIds, Need parent) {
        if (r.allowHarm
                || !parent.allowedSources.contains(SemanticAcquireTaskRecord.Source.HUNT)) {
            return false;
        }
        SemanticAcquireTaskRecord.SourceHint hint = SemanticSourceKnowledge.infer(itemIds);
        return !hint.entityTypeIds().isEmpty() && hint.blockRefs().isEmpty();
    }

    private int recursiveIngredientStructureCost(
            List<ResourceLocation> itemIds, Need parent) {
        Map<StructureKey, Integer> memo = new HashMap<>();
        int best = UNREACHABLE_STRUCTURE_COST;
        for (ResourceLocation itemId : itemIds) {
            if (parent.lineageItems.contains(itemId)) continue;
            best = Math.min(best, recursiveCraftDepth(
                    itemId, parent.lineageItems, new LinkedHashSet<>(), memo,
                    STRUCTURAL_RECIPE_DEPTH));
        }
        return best;
    }

    private NearbySurvey surveyNearby(List<ResourceLocation> ids) {
        Set<ResourceLocation> accepted = Set.copyOf(ids);
        int safe = 0;
        int protectedCount = 0;
        List<Map<String, Object>> samples = new ArrayList<>();
        AABB box = player.getBoundingBox().inflate(r.searchRadius);
        for (ItemEntity item : player.clientLevel.getEntitiesOfClass(
                ItemEntity.class, box, entity -> !entity.isRemoved()
                        && !entity.hasPickUpDelay()
                        && accepted.contains(BuiltInRegistries.ITEM.getKey(
                                entity.getItem().getItem())))) {
            Entity owner = item.getOwner();
            List<String> reasons = new ArrayList<>();
            // Item thrower/target ownership is not part of the ItemStack synced-data field. A null
            // client owner therefore means "not proven", not "wild/free". Only a drop whose owner
            // resolves to this exact LocalPlayer is safe for the broad collector.
            if (owner == null) reasons.add("owner_not_proven_by_client_facts");
            else if (owner != player) reasons.add("owned_by_other_entity");
            reasons.addAll(landmarkReasons(item.blockPosition()));
            if (reasons.isEmpty()) {
                safe++;
            } else {
                protectedCount++;
                if (samples.size() < 8) {
                    samples.add(Map.of(
                            "item_id", BuiltInRegistries.ITEM.getKey(
                                    item.getItem().getItem()).toString(),
                            "reasons", List.copyOf(reasons)));
                }
            }
        }
        return new NearbySurvey(safe, protectedCount, List.copyOf(samples));
    }

    private List<String> landmarkReasons(BlockPos position) {
        return NavigationSafetyContext.protectsMutation(position)
                || NavigationSafetyContext.forbidsBody(position)
                ? List.of("protected_area_cell") : List.of();
    }

    private int count(List<ResourceLocation> ids) {
        int result = 0;
        for (ResourceLocation id : ids) {
            result += PlayerInv.buildableCount(
                    player.getInventory(), BuiltInRegistries.ITEM.get(id));
        }
        return result;
    }

    private Map<ResourceLocation, Integer> counts(List<ResourceLocation> ids) {
        Map<ResourceLocation, Integer> result = new LinkedHashMap<>();
        for (ResourceLocation id : ids) {
            result.put(id, PlayerInv.buildableCount(
                    player.getInventory(), BuiltInRegistries.ITEM.get(id)));
        }
        return Map.copyOf(result);
    }

    private int missing(Need need) {
        return Math.max(0, need.requiredFinalCount - count(need.itemIds));
    }

    private SemanticAcquireTaskRecord.SourceHint sourceHint(Need need) {
        SemanticAcquireTaskRecord.SourceHint inferred =
                SemanticSourceKnowledge.infer(need.itemIds);
        return need.depth == 0
                ? SemanticSourceKnowledge.merge(inferred, r.sourceHint)
                : inferred;
    }

    /**
     * {@code allowed_sources} is a permission set. Rank sources once for this Need from live,
     * side-effect-free readiness facts. Exhausted sources are tracked by identity, so re-ranking
     * after a live fact changes cannot skip or repeat a source by numeric cursor. In particular, a
     * live raw cooking input may outrank recursive crafting, while a
     * hypothetical tool/armour smelt may not outrank an executable ordinary recipe.
     */
    private List<SemanticAcquireTaskRecord.Source> executionSourceOrder(Need need) {
        if (need.plannedSourceOrder != null) return need.plannedSourceOrder;
        SemanticAcquireTaskRecord.SourceHint hint = sourceHint(need);
        boolean naturalMine = !hint.blockRefs().isEmpty();
        boolean directHunt = r.allowHarm && !hint.entityTypeIds().isEmpty();
        boolean craftReady = craftExecutableNow(need);
        boolean cookReady = cookInputReadyNow(need);
        need.plannedSourceOrder = need.allowedSources.stream()
                .filter(source -> !need.exhaustedSources.contains(source))
                .sorted(Comparator.comparingInt(source -> switch (source) {
                    case INVENTORY -> 0;
                    case NEARBY -> 10;
                    case STORAGE -> 20;
                    case CRAFT -> craftReady ? 25 : 50;
                    case COOK -> cookReady ? 26 : 55;
                    case MINE -> naturalMine ? 30 : 60;
                    case HUNT -> directHunt ? 35 : 80;
                    case TRADE -> 70;
                }))
                .toList();
        return need.plannedSourceOrder;
    }

    private boolean craftExecutableNow(Need need) {
        Set<String> excluded = new LinkedHashSet<>(need.lineageRecipes);
        excluded.addAll(need.rejectedRecipes);
        excluded.addAll(nonCommittedCraftRecipes(need));
        CraftingWorkstationCoordinator.PlanningSnapshot workstation =
                CraftOps.requiresWorkstationForAny(need.itemIds, player)
                        ? CraftingWorkstationCoordinator.inspect(player) : null;
        int deficit = missing(need);
        for (ResourceLocation output : need.itemIds) {
            int requested = PlayerInv.buildableCount(
                    player.getInventory(), BuiltInRegistries.ITEM.get(output)) + deficit;
            ToolContext context = new ToolContext(
                    (r.getToolCallId() == null ? "acquire" : r.getToolCallId())
                            + "-source-rank",
                    player.level().getGameTime());
            if (craftOps.plan(output.toString(), requested, player, context,
                    workstation, excluded).executable()) return true;
        }
        return false;
    }

    private boolean cookInputReadyNow(Need need) {
        Set<Item> outputs = need.itemIds.stream()
                .map(BuiltInRegistries.ITEM::get)
                .collect(java.util.stream.Collectors.toSet());
        int deficit = missing(need);
        for (var holder : ClientRuntime.requireContext(player)
                .connection().getRecipeManager().getRecipes()) {
            if (!(holder.value() instanceof AbstractCookingRecipe cooking)) continue;
            if (!RecipeProbe.usableIngredients(cooking)) continue;
            ItemStack result = RecipeProbe.resultOf(
                    cooking, player.level().registryAccess());
            if (result.isEmpty() || !outputs.contains(result.getItem())) continue;
            int batches = Math.max(1,
                    (deficit + Math.max(1, result.getCount()) - 1)
                            / Math.max(1, result.getCount()));
            if (cooking.getIngredients().isEmpty()) continue;
            Ingredient input = cooking.getIngredients().getFirst();
            int available = 0;
            int slots = Math.min(
                    PlayerInv.BUILDABLE_SLOTS, player.getInventory().getContainerSize());
            for (int slot = 0; slot < slots; slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (!stack.isEmpty() && input.test(stack)) available += stack.getCount();
            }
            if (available >= batches) return true;
        }
        return false;
    }

    /**
     * Gate only the physical source currently under consideration. Inventory, storage, recipes,
     * cooking and trade have already had (or will still receive) their own independent chance.
     * A failed gate advances this one source without spending work or constructing a child.
     */
    private boolean sourceDimensionAllowed(
            Need need, SemanticAcquireTaskRecord.Source source) {
        List<ResourceLocation> allowed = SemanticSourceKnowledge.inferPlan(need.itemIds)
                .allowedDimensions(source);
        if (allowed.isEmpty()) return true;
        ResourceLocation current = player.level().dimension().location();
        if (allowed.contains(current)) return true;

        rememberDimensionBarrier(need, allowed, current);
        addIssue(source.name().toLowerCase(java.util.Locale.ROOT), "requires_dimension",
                "this physical source family is not valid in the current dimension; no movement "
                        + "or attack was started",
                Map.of("target_item_family", itemStrings(need.itemIds),
                        "allowed_dimensions", stringIds(allowed),
                        "current_dimension", current.toString()));
        advanceSource(need);
        return false;
    }

    private void rememberDimensionBarrier(
            Need need,
            List<ResourceLocation> allowed,
            ResourceLocation current) {
        for (int index = 0; index < dimensionBarriers.size(); index++) {
            DimensionBarrier known = dimensionBarriers.get(index);
            if (!known.itemIds().equals(need.itemIds)
                    || !known.currentDimension().equals(current)) {
                continue;
            }
            LinkedHashSet<ResourceLocation> merged = new LinkedHashSet<>(
                    known.allowedDimensions());
            merged.addAll(allowed);
            dimensionBarriers.set(index, new DimensionBarrier(
                    known.itemIds(), List.copyOf(merged), current,
                    Math.min(known.depth(), need.depth)));
            return;
        }
        dimensionBarriers.add(new DimensionBarrier(
                need.itemIds, allowed, current, need.depth));
    }

    private DimensionBarrier preferredDimensionBarrier() {
        return dimensionBarriers.stream()
                .min(Comparator
                        .comparingInt(DimensionBarrier::depth)
                        .thenComparing(barrier -> String.join(",", itemStrings(
                                barrier.itemIds()))))
                .orElse(null);
    }

    private boolean takePlannerStep() {
        if (plannerStepsThisTick >= PLANNER_STEPS_PER_TICK) return false;
        plannerStepsThisTick++;
        return true;
    }

    private void renewProgressLease() {
        r.extendDeadlineTo(player.level().getGameTime() + PROGRESS_LEASE_TICKS);
    }

    private void advanceSource(Need need) {
        List<SemanticAcquireTaskRecord.Source> remaining = executionSourceOrder(need);
        if (!remaining.isEmpty()) need.exhaustedSources.add(remaining.getFirst());
        need.plannedSourceOrder = null;
    }

    private String childId(String kind) {
        String parent = r.getToolCallId() == null ? "acquire" : r.getToolCallId();
        return parent + "-internal-" + kind + '-' + (++childSerial);
    }

    private TaskState failAcquisition(String code, String message, FailureType type) {
        failureCode = code;
        if (failureNeed == null) failureNeed = needs.peek();
        fail(message, type);
        return TaskState.FAILED;
    }

    private void addIssue(
            String source, String code, String summary, Map<String, ?> facts) {
        if (issues.size() >= MAX_REPORTED_ISSUES) return;
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("source", source);
        issue.put("code", code);
        issue.put("summary", summary == null ? "" : summary);
        if (facts != null && !facts.isEmpty()) issue.put("facts", new LinkedHashMap<>(facts));
        issues.add(Map.copyOf(issue));
    }

    private void recordAttempt(
            TaskState state,
            TaskResult result,
            int after,
            int progress,
            boolean stoppedBecauseSatisfied) {
        if (attempts.size() >= MAX_REPORTED_ATTEMPTS) return;
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("source", activeSource.name().toLowerCase());
        attempt.put("detail", activeDetail);
        attempt.put("child_tool", activeRecord.getToolName());
        attempt.put("terminal_state", state.name().toLowerCase());
        attempt.put("inventory_before", activeBeforeCount);
        attempt.put("inventory_after", after);
        attempt.put("inventory_progress", progress);
        attempt.put("effects_observed",
                activeChildEffectsObserved(state, result, progress));
        attempt.put("stopped_because_final_fact_satisfied", stoppedBecauseSatisfied);
        if (result != null) {
            attempt.put("child_success", result.success());
            attempt.put("child_message", result.message() == null ? "" : result.message());
            if (result.data() != null && !result.data().isEmpty()) {
                attempt.put("child_data", childDataForAttempt(result.data()));
            }
        }
        attempts.add(Map.copyOf(attempt));
    }

    private Map<String, Object> childDataForAttempt(Map<String, Object> data) {
        if (activeSource != SemanticAcquireTaskRecord.Source.HUNT
                || !AttackTaskRecord.TOOL_NAME.equals(activeRecord.getToolName())) {
            return data;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("mode", "semantic_loaded_target");
        copyIfPresent(data, safe, "requested_targets");
        copyIfPresent(data, safe, "defeated_targets");
        copyIfPresent(data, safe, "lost_targets");
        copyIfPresent(data, safe, "unreachable_targets");
        copyIfPresent(data, safe, "strikes");
        copyIfPresent(data, safe, "loot_gained");
        copyIfPresent(data, safe, "unreachable_drop_count");
        return Map.copyOf(safe);
    }

    private static void copyIfPresent(
            Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private Map<String, Object> needFacts(Need need) {
        return Map.of(
                "item_ids", itemStrings(need.itemIds),
                "required_final_count", need.requiredFinalCount,
                "observed_final_count", count(need.itemIds),
                "missing", missing(need),
                "depth", need.depth);
    }

    private List<Map<String, Object>> recoveryOptions() {
        List<Map<String, Object>> options = new ArrayList<>();
        Set<SemanticAcquireTaskRecord.Source> allowed = Set.copyOf(r.allowedSources);
        for (SemanticAcquireTaskRecord.Source source : List.of(
                SemanticAcquireTaskRecord.Source.STORAGE,
                SemanticAcquireTaskRecord.Source.COOK,
                SemanticAcquireTaskRecord.Source.MINE,
                SemanticAcquireTaskRecord.Source.TRADE,
                SemanticAcquireTaskRecord.Source.HUNT)) {
            if (!allowed.contains(source)) {
                Map<String, Object> patch = Map.of(
                        "allowed_sources", appendSource(r.allowedSources, source));
                options.add(Map.of(
                        "id", "allow_" + source.name().toLowerCase(),
                        "summary", "Retry the same inventory outcome while permitting "
                                + source.name().toLowerCase() + " as a semantic source family.",
                        "risk", source == SemanticAcquireTaskRecord.Source.HUNT
                                ? "high" : source == SemanticAcquireTaskRecord.Source.MINE
                                ? "medium" : "low",
                        "parameters_patch", patch));
            }
        }
        if (SemanticSourceKnowledge.merge(
                SemanticSourceKnowledge.infer(r.itemIds), r.sourceHint).isEmpty()) {
            options.add(Map.of(
                    "id", "provide_source_hint",
                    "summary", "Provide only a semantic source family (block/tag, entity type or "
                            + "trade profession), never coordinates or runtime entity IDs.",
                    "risk", "none"));
        }
        if (allowed.contains(SemanticAcquireTaskRecord.Source.HUNT) && !r.allowHarm) {
            options.add(Map.of(
                    "id", "narrate_and_allow_harm",
                    "summary", "After telling the audience what would be harmed and why, retry "
                            + "with allow_harm=true. Protection ambiguity still stops execution.",
                    "risk", "high",
                    "parameters_patch", Map.of("allow_harm", true)));
        }
        if (allowed.contains(SemanticAcquireTaskRecord.Source.HUNT)
                && (hasIssue("hunt_entity_search_exhausted")
                        || hasIssue("hunt_entity_search_incomplete"))) {
            options.add(Map.of(
                    "id", "continue_from_another_semantic_area",
                    "summary", "Continue from another unprotected semantic area, then retry the "
                            + "same inventory outcome; MaiCraft will run another progress-driven first-person search.",
                    "risk", "none_until_a_new_target_is_verified_and_harm_is_rechecked"));
        }
        if (allowed.contains(SemanticAcquireTaskRecord.Source.HUNT)
                && hasIssue("protected_hunt_target_requires_decision")) {
            options.add(Map.of(
                    "id", "choose_unprotected_hunt_context",
                    "summary", "Choose or travel to a clearly unprotected matching population; "
                            + "the current named, tamed, player, managed or protected candidates "
                            + "will not be attacked.",
                    "risk", "high_after_a_new_target_is_verified"));
        }
        options.add(Map.of(
                "id", "semantic_prerequisite",
                "summary", "Run a semantic prerequisite such as preparing a crafting surface or "
                        + "a suitable harvesting tool, then retry the unchanged item fact.",
                "risk", "depends_on_prerequisite"));
        Map<String, Object> stop = Map.of(
                "id", "stop",
                "summary", "Leave the inventory fact incomplete and perform no further effects.",
                "risk", "none");
        List<Map<String, Object>> reported = new ArrayList<>(
                options.stream().limit(7).toList());
        reported.add(stop);
        return List.copyOf(reported);
    }

    private boolean hasIssue(String code) {
        return issues.stream().anyMatch(issue -> code.equals(issue.get("code")));
    }

    private static List<String> appendSource(
            List<SemanticAcquireTaskRecord.Source> sources,
            SemanticAcquireTaskRecord.Source extra) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (var source : sources) result.add(source.name().toLowerCase());
        result.add(extra.name().toLowerCase());
        return List.copyOf(result);
    }

    @Override
    protected Map<String, Object> resultData() {
        if ("requires_dimension".equals(failureCode) && failureDimension != null) {
            return dimensionFailureData();
        }
        Map<ResourceLocation, Integer> current = counts(r.itemIds);
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        for (ResourceLocation id : r.itemIds) {
            before.put(id.toString(), initialCounts.getOrDefault(id, 0));
            after.put(id.toString(), current.getOrDefault(id, 0));
        }
        int observed = count(r.itemIds);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("goal", "final_main_inventory_count");
        data.put("item_ids", itemStrings(r.itemIds));
        data.put("required_final_count", r.count);
        data.put("observed_final_count", observed);
        data.put("missing", Math.max(0, r.count - observed));
        data.put("goal_satisfied", observed >= r.count);
        data.put("inventory_before_by_item", before);
        data.put("inventory_after_by_item", after);
        data.put("allowed_sources", r.allowedSources.stream()
                .map(source -> source.name().toLowerCase()).toList());
        data.put("allow_harm", r.allowHarm);
        data.put("harmless_hunt_retargets", harmlessHuntRetargets);
        data.put("attempts", List.copyOf(attempts));
        data.put("recipe_trace", List.copyOf(recipeTrace));
        data.put("issues", List.copyOf(issues));
        data.put("outcome_uncertain", outcomeUncertain);
        boolean effectsObserved =
                (rootNeed != null && rootNeed.effectsObserved)
                        || (failureNeed != null && failureNeed.effectsObserved)
                        || attempts.stream().anyMatch(
                                attempt -> bool(attempt.get("effects_observed")));
        data.put("effects_observed", effectsObserved);
        data.put("partial_effects_observed", observed < r.count && effectsObserved);
        if (failureCode != null) {
            data.put("failure_code", failureCode);
            data.put("requires_decision", true);
            data.put("requires_narration",
                    failureRequiresNarration(failureCode)
                            || issues.stream().anyMatch(
                                    SemanticAcquireCompanionTask::issueRequiresNarration));
            Need blocked = failureNeed == null ? needs.peek() : failureNeed;
            if (blocked != null) {
                data.put("blocked_need", needFacts(blocked));
                Set<String> routeIds = !blocked.parentRecipeIds.isEmpty()
                        ? blocked.parentRecipeIds : blocked.committedRecipeIds;
                if (!routeIds.isEmpty()) {
                    data.put("route_recipe_ids", List.copyOf(routeIds));
                }
            }
            data.put("recovery_options", recoveryOptions());
        }
        return data;
    }

    private static boolean failureRequiresNarration(String code) {
        return code != null && (code.contains("decision_required")
                || "hunt_loot_settlement_incomplete".equals(code)
                || code.startsWith("committed_recipe_"));
    }

    private static boolean issueRequiresNarration(Map<String, Object> issue) {
        if (bool(issue.get("requires_narration"))) return true;
        Object rawFacts = issue.get("facts");
        if (rawFacts instanceof Map<?, ?> facts
                && bool(facts.get("requires_narration"))) return true;
        Object code = issue.get("code");
        return "harm_permission_required".equals(code)
                || "protected_hunt_target_requires_decision".equals(code)
                || "hunt_entity_search_exhausted".equals(code)
                || "hunt_entity_search_incomplete".equals(code)
                || "expected_hunt_drop_not_observed".equals(code)
                || "drop_ownership_ambiguous".equals(code);
    }

    private Map<String, Object> dimensionFailureData() {
        List<String> allowed = stringIds(failureDimension.allowedDimensions());
        Map<String, Object> travel = Map.of(
                "id", "travel_dimension",
                "summary", "Travel through an independently authorized dimension route, then "
                        + "retry the unchanged semantic inventory goal.",
                "allowed_dimensions", allowed,
                "risk", "dimension_travel_requires_separate_authorization");
        Map<String, Object> stop = Map.of(
                "id", "stop",
                "summary", "Leave the inventory goal incomplete and perform no further effects.",
                "risk", "none");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("failure_type", "requires_dimension");
        data.put("target_item_family", itemStrings(failureDimension.itemIds()));
        data.put("allowed_dimensions", allowed);
        data.put("current_dimension", failureDimension.currentDimension().toString());
        data.put("requires_decision", true);
        data.put("requires_narration", true);
        data.put("recovery_options", List.of(travel, stop));
        return Map.copyOf(data);
    }

    @Override
    protected String successMessage() {
        return "final inventory fact satisfied: carrying " + count(r.itemIds)
                + "/" + r.count + " across acceptable items " + itemStrings(r.itemIds);
    }

    @Override
    protected String timeoutMessage() {
        if (failureCode == null) {
            failureCode = "acquisition_timeout";
            addIssue("planner", "acquisition_timeout",
                    "the no-progress lease elapsed before the final inventory fact was true",
                    Map.of("observed_final_count", count(r.itemIds),
                            "required_final_count", r.count));
        }
        return "semantic acquisition timed out at " + count(r.itemIds) + "/" + r.count
                + "; review attempts before retrying";
    }

    @Override
    protected String cancelledMessage() {
        return "semantic acquisition was interrupted at " + count(r.itemIds) + "/" + r.count;
    }

    @Override
    protected void cleanup() {
        if (activeChild != null) {
            activeChild.stop(player, Task.StopReason.REPLACED);
            TaskResult childResult = activeChild.result(TaskState.CANCELLED);
            int progress = Math.max(
                    0, count(activeNeed.itemIds) - activeBeforeCount);
            markActiveEffectsIfObserved(TaskState.CANCELLED, childResult, progress);
            if (childResult != null && childResult.data() != null
                    && bool(childResult.data().get("outcome_uncertain"))) {
                outcomeUncertain = true;
            }
            recordAttempt(TaskState.CANCELLED, childResult,
                    count(activeNeed.itemIds), progress, false);
            clearActive();
        }
        super.cleanup();
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean flag && flag;
    }

    private static CraftPlanCost.Surface craftSurface(
            Object raw, boolean supported, boolean ready, boolean preparable) {
        String value = string(raw);
        if (value != null) {
            try {
                return CraftPlanCost.Surface.valueOf(value.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Fall through to the backwards-compatible booleans below.
            }
        }
        if (!supported) return CraftPlanCost.Surface.UNSUPPORTED;
        if (ready) return CraftPlanCost.Surface.READY;
        return preparable
                ? CraftPlanCost.Surface.PREPARABLE
                : CraftPlanCost.Surface.UNAVAILABLE;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> itemStrings(List<ResourceLocation> ids) {
        return ids.stream().map(ResourceLocation::toString).toList();
    }

    private static List<String> stringIds(java.util.Collection<ResourceLocation> ids) {
        return ids.stream().map(ResourceLocation::toString).sorted().toList();
    }

    private static String shortLabel(List<ResourceLocation> ids) {
        String first = ids.getFirst().getPath();
        return ids.size() == 1 ? first : first + "+" + (ids.size() - 1);
    }

    private static String blockLabel(Set<Block> blocks) {
        String first = BuiltInRegistries.BLOCK.getKey(blocks.iterator().next()).getPath();
        return blocks.size() == 1 ? first : first + "+" + (blocks.size() - 1);
    }

    private static List<String> blockStrings(Set<Block> blocks) {
        return blocks.stream().map(BuiltInRegistries.BLOCK::getKey)
                .map(ResourceLocation::toString).sorted().toList();
    }
}
