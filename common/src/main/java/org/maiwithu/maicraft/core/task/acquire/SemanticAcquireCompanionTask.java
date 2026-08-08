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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.collect.CollectItemsTaskRecord;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.core.task.cook.SemanticCookTaskRecord;
import org.maiwithu.maicraft.core.task.entity.EntitySemanticSafety;
import org.maiwithu.maicraft.core.task.entity.GenericEntitySearchCompanionTask;
import org.maiwithu.maicraft.core.task.entity.GenericEntitySearchTaskRecord;
import org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord;
import org.maiwithu.maicraft.core.task.trade.SemanticTradeTaskRecord;
import org.maiwithu.maicraft.core.tools.CraftOps;
import org.maiwithu.maicraft.core.tools.ToolParse;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * A bounded semantic acquisition coordinator. It never performs a physical action itself: every
 * effect is a child task, and every transition is based on a terminal receipt plus a fresh live
 * main-inventory observation.
 */
public final class SemanticAcquireCompanionTask
        extends AbstractCompanionTask<SemanticAcquireTaskRecord> {
    private static final int MAX_REPORTED_ATTEMPTS = 64;
    private static final int MAX_REPORTED_ISSUES = 64;
    private static final int MAX_SOURCE_ATTEMPTS = 3;
    private static final int LANDMARK_PROTECTION_RADIUS = 12;
    private static final int MINE_TASK_SEARCH_REACH = 32 * 16 + 16;
    private static final long COLLECT_TICKS = 60L * 20L;
    private static final long MINE_MIN_TICKS = 60L * 20L;
    private static final long MINE_PER_UNIT_TICKS = 30L * 20L;
    private static final long STORAGE_TICKS = 10L * 60L * 20L;
    private static final long HUNT_TICKS = 120L * 20L;
    private static final int MAX_HUNT_TARGETS = 16;
    private static final int MAX_HUNT_SEARCHES = 3;
    private static final int HUNT_SEARCH_DISTANCE = 512;
    private static final int MAX_TRADE_ALTERNATIVES = 8;

    private enum HuntChildStage { NONE, SEARCH, ATTACK }

    private static final class Need {
        final List<ResourceLocation> itemIds;
        final int requiredFinalCount;
        final int depth;
        final Set<ResourceLocation> lineageItems;
        final Set<String> lineageRecipes;
        final String parentRecipeId;
        final Set<String> rejectedRecipes = new LinkedHashSet<>();
        final Map<SemanticAcquireTaskRecord.Source, Integer> sourceAttempts =
                new LinkedHashMap<>();
        int sourceCursor;
        int craftRounds;
        int huntSearchAttempts;
        boolean huntSearchExpandedView;
        boolean miningToolPrerequisitePushed;

        Need(
                List<ResourceLocation> itemIds,
                int requiredFinalCount,
                int depth,
                Set<ResourceLocation> lineageItems,
                Set<String> lineageRecipes,
                String parentRecipeId) {
            this.itemIds = List.copyOf(itemIds);
            this.requiredFinalCount = requiredFinalCount;
            this.depth = depth;
            this.lineageItems = Set.copyOf(lineageItems);
            this.lineageRecipes = Set.copyOf(lineageRecipes);
            this.parentRecipeId = parentRecipeId;
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
            int missing,
            boolean surfaceSupported,
            boolean surfaceReady) {}

    private record IngredientNeed(
            List<ResourceLocation> itemIds, int missing, Map<String, Object> fact) {}

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
    private Map<ResourceLocation, Integer> initialCounts = Map.of();
    private Need rootNeed;
    private Task activeChild;
    private TaskRecord activeRecord;
    private Need activeNeed;
    private SemanticAcquireTaskRecord.Source activeSource;
    private String activeDetail;
    private int activeBeforeCount;
    private HuntChildStage activeHuntStage = HuntChildStage.NONE;
    private UUID activeHuntTarget;
    private final Set<UUID> rejectedHuntTargets = new LinkedHashSet<>();
    private int childSerial;
    private int workUsed;
    private String failureCode;
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
                null);
        needs.push(rootNeed);
    }

    @Override
    protected TaskState onTick() {
        // This check deliberately precedes child advancement. A child may have made the semantic
        // fact true on the previous tick; no menu cleanup, recipe branch or mining swing is allowed
        // to continue merely because its internal task has not yet declared terminal success.
        if (count(r.itemIds) >= r.count) {
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
        if (count(need.itemIds) >= need.requiredFinalCount) {
            needs.pop();
            return TaskState.RUNNING;
        }
        if (workUsed >= r.workBudget) {
            addIssue("planner", "work_budget_exhausted",
                    "the bounded acquisition work budget was exhausted",
                    Map.of("work_budget", r.workBudget, "work_used", workUsed,
                            "need", itemStrings(need.itemIds)));
            return exhaustNeed(need);
        }
        if (need.sourceCursor >= r.allowedSources.size()) {
            return exhaustNeed(need);
        }

        SemanticAcquireTaskRecord.Source source = r.allowedSources.get(need.sourceCursor);
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
        if (need.attempts(SemanticAcquireTaskRecord.Source.NEARBY) >= 1) {
            advanceSource(need);
            return TaskState.RUNNING;
        }
        if (!sourceDimensionAllowed(need, SemanticAcquireTaskRecord.Source.NEARBY)) {
            return TaskState.RUNNING;
        }
        if (!spendWork()) return exhaustNeed(need);
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
        if (need.attempts(SemanticAcquireTaskRecord.Source.STORAGE) >= MAX_SOURCE_ATTEMPTS) {
            advanceSource(need);
            return TaskState.RUNNING;
        }
        if (!Ae2ResourceSupply.available()) {
            addIssue("storage", "storage_adapter_unavailable",
                    "no supported client storage-network adapter is available",
                    Map.of("detail", Ae2ResourceSupply.availabilityDetail()));
            advanceSource(need);
            return TaskState.RUNNING;
        }
        int missing = missing(need);
        if (missing <= 0) return TaskState.RUNNING;
        if (!spendWork()) return exhaustNeed(need);
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
        if (need.craftRounds >= Math.max(4, r.maxRecipeDepth * 3)) {
            addIssue("craft", "recipe_work_exhausted",
                    "crafting did not close this need within its bounded recipe rounds",
                    Map.of("depth", need.depth, "rounds", need.craftRounds,
                            "rejected_recipes", List.copyOf(need.rejectedRecipes)));
            advanceSource(need);
            return TaskState.RUNNING;
        }
        if (need.depth > r.maxRecipeDepth) {
            addIssue("craft", "recipe_depth_exhausted",
                    "recursive crafting reached the configured depth limit",
                    Map.of("depth", need.depth, "max_recipe_depth", r.maxRecipeDepth));
            advanceSource(need);
            return TaskState.RUNNING;
        }

        int deficit = missing(need);
        List<CraftCandidate> candidates = new ArrayList<>();
        for (ResourceLocation output : need.itemIds) {
            if (!spendWork()) break;
            int requestedOwnFinal = PlayerInv.buildableCount(
                    player.getInventory(), BuiltInRegistries.ITEM.get(output)) + deficit;
            ToolContext context = new ToolContext(
                    childId("craft-plan"), player.level().getGameTime());
            CraftOps.Plan plan = craftOps.plan(
                    output.toString(), requestedOwnFinal, player, context);
            if (plan.executable()) {
                need.craftRounds++;
                need.attempted(SemanticAcquireTaskRecord.Source.CRAFT);
                return startChild(need, SemanticAcquireTaskRecord.Source.CRAFT,
                        plan.task(), "craft " + output + " from live recipe facts");
            }
            collectCraftCandidates(output, plan.immediate(), candidates);
        }

        CraftCandidate chosen = candidates.stream()
                .filter(candidate -> !need.rejectedRecipes.contains(candidate.recipeId()))
                .filter(candidate -> !need.lineageRecipes.contains(candidate.recipeId()))
                .filter(CraftCandidate::surfaceSupported)
                .filter(candidate -> candidate.missing() > 0)
                .sorted(Comparator
                        .comparingInt(CraftCandidate::missing)
                        .thenComparing(Comparator.comparing(
                                CraftCandidate::surfaceReady).reversed())
                        .thenComparing(CraftCandidate::recipeId))
                .findFirst().orElse(null);
        if (chosen == null) {
            boolean surfaceMissing = candidates.stream().anyMatch(candidate ->
                    candidate.missing() == 0
                            && candidate.surfaceSupported() && !candidate.surfaceReady());
            addIssue("craft", surfaceMissing
                            ? "crafting_surface_missing" : "no_bounded_recipe_path",
                    surfaceMissing
                            ? "materials exist, but no compatible loaded crafting surface is ready"
                            : "no cycle-free client-known ordinary recipe path remained",
                    Map.of("candidate_count", candidates.size(),
                            "rejected_recipes", List.copyOf(need.rejectedRecipes),
                            "lineage_recipes", List.copyOf(need.lineageRecipes)));
            advanceSource(need);
            return TaskState.RUNNING;
        }

        IngredientNeed ingredient = chooseIngredient(chosen, need);
        if (ingredient == null) {
            need.rejectedRecipes.add(chosen.recipeId());
            addIssue("craft", "recipe_cycle_or_missing_evidence",
                    "the closest recipe's missing ingredients were cyclical or did not expose "
                            + "acceptable item IDs",
                    Map.of("recipe_id", chosen.recipeId()));
            return TaskState.RUNNING;
        }
        if (need.depth >= r.maxRecipeDepth) {
            need.rejectedRecipes.add(chosen.recipeId());
            addIssue("craft", "recipe_depth_exhausted",
                    "the next missing ingredient would exceed the recursive recipe depth",
                    Map.of("recipe_id", chosen.recipeId(),
                            "max_recipe_depth", r.maxRecipeDepth,
                            "ingredient", itemStrings(ingredient.itemIds())));
            return TaskState.RUNNING;
        }

        Set<ResourceLocation> lineageItems = new LinkedHashSet<>(need.lineageItems);
        lineageItems.addAll(ingredient.itemIds());
        Set<String> lineageRecipes = new LinkedHashSet<>(need.lineageRecipes);
        lineageRecipes.add(chosen.recipeId());
        int ingredientFinal = count(ingredient.itemIds()) + ingredient.missing();
        Need childNeed = new Need(
                ingredient.itemIds(), ingredientFinal, need.depth + 1,
                lineageItems, lineageRecipes, chosen.recipeId());
        need.craftRounds++;
        recipeTrace.add(Map.of(
                "recipe_id", chosen.recipeId(),
                "output_item_id", chosen.outputItem().toString(),
                "depth", need.depth,
                "missing_item_ids", itemStrings(ingredient.itemIds()),
                "missing_count", ingredient.missing()));
        needs.push(childNeed);
        return TaskState.RUNNING;
    }

    private TaskState attemptMine(Need need) {
        if (need.attempts(SemanticAcquireTaskRecord.Source.MINE) >= MAX_SOURCE_ATTEMPTS) {
            advanceSource(need);
            return TaskState.RUNNING;
        }
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
        SemanticSourceKnowledge.ToolRequirement tool =
                SemanticSourceKnowledge.missingTool(player, blocks);
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
            if (need.depth >= r.maxRecipeDepth) {
                addIssue("mine", "tool_recipe_depth_exhausted",
                        "preparing a suitable harvesting tool would exceed the recursive depth",
                        Map.of("tool_family", tool.toolFamily(),
                                "minimum_tier", tool.minimumTier()));
                advanceSource(need);
                return TaskState.RUNNING;
            }
            Set<ResourceLocation> lineage = new LinkedHashSet<>(need.lineageItems);
            lineage.addAll(tool.acceptableItemIds());
            Need toolNeed = new Need(
                    tool.acceptableItemIds(), count(tool.acceptableItemIds()) + 1,
                    need.depth + 1, lineage, need.lineageRecipes, null);
            need.miningToolPrerequisitePushed = true;
            addIssue("mine", "preparing_harvesting_tool",
                    "a suitable harvesting tool is a semantic prerequisite for the observed "
                            + "source block family",
                    Map.of("tool_family", tool.toolFamily(),
                            "minimum_tier", tool.minimumTier(),
                            "acceptable_tool_count", tool.acceptableItemIds().size()));
            needs.push(toolNeed);
            return TaskState.RUNNING;
        }
        List<String> protectionProblems = worldSourceProtectionProblems(MINE_TASK_SEARCH_REACH);
        if (!protectionProblems.isEmpty()) {
            addIssue("mine", "protected_area_scope_ambiguous",
                    "the generic mining child can search a wider loaded field than the protected "
                            + "areas it would need to exclude, so mining was not started",
                    Map.of("protection_evidence", protectionProblems,
                            "source_blocks", blockStrings(blocks)));
            advanceSource(need);
            return TaskState.RUNNING;
        }
        if (!spendWork()) return exhaustNeed(need);
        need.attempted(SemanticAcquireTaskRecord.Source.MINE);
        int deficit = Math.min(256, missing(need));
        long now = player.level().getGameTime();
        long budget = Math.max(MINE_MIN_TICKS, deficit * MINE_PER_UNIT_TICKS);
        MineBlockTaskRecord record = new MineBlockTaskRecord(
                childId("mine"), now + budget, blocks, deficit, blockLabel(blocks));
        return startChild(need, SemanticAcquireTaskRecord.Source.MINE,
                record, "mine BlockItem-derived or semantic source blocks");
    }

    private TaskState attemptCook(Need need) {
        if (need.attempts(SemanticAcquireTaskRecord.Source.COOK) >= MAX_SOURCE_ATTEMPTS) {
            advanceSource(need);
            return TaskState.RUNNING;
        }
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
        if (!spendWork()) return exhaustNeed(need);
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
                Math.min(r.getDeadlineGameTime(), now + 12L * 60L * 20L),
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
            ItemStack result = cooking.getResultItem(player.level().registryAccess());
            if (!result.isEmpty() && result.is(output)) return true;
        }
        return false;
    }

    private TaskState reviewTrade(Need need) {
        if (!spendWork()) return exhaustNeed(need);
        need.attempted(SemanticAcquireTaskRecord.Source.TRADE);
        int alternativeCount = Math.min(MAX_TRADE_ALTERNATIVES, need.itemIds.size());
        int alternativeIndex = Math.floorMod(
                need.attempts(SemanticAcquireTaskRecord.Source.TRADE) - 1,
                Math.max(1, alternativeCount));
        ResourceLocation output = need.itemIds.get(alternativeIndex);
        int currentSelected = PlayerInv.buildableCount(
                player.getInventory(), BuiltInRegistries.ITEM.get(output));
        int selectedFinalCount = Math.min(
                SemanticTradeTaskRecord.MAX_FINAL_COUNT,
                currentSelected + missing(need));
        long now = player.level().getGameTime();
        SemanticTradeTaskRecord record = new SemanticTradeTaskRecord(
                childId("trade"),
                Math.min(r.getDeadlineGameTime(), now + 8L * 60L * 20L),
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
            addIssue("hunt", "harm_permission_required",
                    "hunt was allowed as a source family, but allow_harm is false; no entity was attacked",
                    Map.of("requires_narration", true));
            advanceSource(need);
            return TaskState.RUNNING;
        }
        int attemptLimit = huntAttemptLimit(need);
        if (need.attempts(SemanticAcquireTaskRecord.Source.HUNT) >= attemptLimit) {
            addIssue("hunt", "hunt_attempt_limit_reached",
                    "the bounded hunt target budget ended before the inventory fact became true",
                    Map.of("attempted_targets", need.attempts(
                                    SemanticAcquireTaskRecord.Source.HUNT),
                            "target_budget", attemptLimit,
                            "observed_final_count", count(need.itemIds),
                            "required_final_count", need.requiredFinalCount));
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
        List<Entity> safe = new ArrayList<>();
        List<Map<String, Object>> protectedCandidates = new ArrayList<>();
        int loadedRadius = need.huntSearchExpandedView
                ? GenericEntitySearchCompanionTask.LOADED_EVIDENCE_RADIUS
                : r.searchRadius;
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
            Map<String, Object> fact = Map.of(
                    "entity_type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    "protection_evidence", reasons);
            if (reasons.isEmpty()) safe.add(entity); else protectedCandidates.add(fact);
        }
        safe.sort(Comparator.comparingDouble(player::distanceToSqr));
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("safe_candidate_count", safe.size());
        facts.put("protected_or_ambiguous_candidate_count", protectedCandidates.size());
        facts.put("protected_candidate_samples", protectedCandidates.stream().limit(8).toList());
        facts.put("expected_item_ids", stringIds(expected));
        facts.put("relation", relation.name().toLowerCase(java.util.Locale.ROOT));
        facts.put("searched_loaded_radius", loadedRadius);
        if (safe.isEmpty()) {
            if (!protectedCandidates.isEmpty()) {
                addIssue("hunt", "protected_hunt_target_requires_decision",
                        "every matching loaded entity is named, tamed, owned, leashed, persistent, "
                                + "near another player, inside protected/remembered terrain, "
                                + "enclosed, or not fully observable",
                        facts);
                advanceSource(need);
                return TaskState.RUNNING;
            }
            if (need.huntSearchAttempts >= MAX_HUNT_SEARCHES) {
                addIssue("hunt", "hunt_entity_search_exhausted",
                        "bounded first-person entity searches ended without loading an acceptable target",
                        Map.of("search_attempts", need.huntSearchAttempts,
                                "max_search_distance", HUNT_SEARCH_DISTANCE,
                                "entity_type_ids", stringIds(hint.entityTypeIds()),
                                "relation", relation.name().toLowerCase(java.util.Locale.ROOT)));
                advanceSource(need);
                return TaskState.RUNNING;
            }
            if (!spendWork()) return exhaustNeed(need);
            need.huntSearchAttempts++;
            need.huntSearchExpandedView = false;
            addIssue("hunt", "no_loaded_hunt_evidence_searching",
                    "no acceptable target is currently loaded; starting bounded first-person frontier search",
                    facts);
            long now = player.level().getGameTime();
            GenericEntitySearchTaskRecord search = new GenericEntitySearchTaskRecord(
                    childId("hunt-search"),
                    Math.min(r.getDeadlineGameTime(), now + 10L * 60L * 20L),
                    hint.entityTypeIds(), relation, 1, HUNT_SEARCH_DISTANCE,
                    false, r.protectedLabels, true);
            return startHuntChild(need, search, HuntChildStage.SEARCH, null,
                    "find an unprotected semantic hunt source through first-person exploration");
        }

        if (EntitySemanticSafety.otherPlayerNearby(player, safe.getFirst())) {
            addIssue("hunt", "other_player_near_hunt_target",
                    "another player entered the target area; no harm was initiated",
                    Map.of("entity_type", BuiltInRegistries.ENTITY_TYPE.getKey(
                                    safe.getFirst().getType()).toString(),
                            "requires_narration", true));
            advanceSource(need);
            return TaskState.RUNNING;
        }

        if (!spendWork()) return exhaustNeed(need);
        need.attempted(SemanticAcquireTaskRecord.Source.HUNT);
        Entity target = safe.getFirst();
        long now = player.level().getGameTime();
        AttackTaskRecord attack = new AttackTaskRecord(
                childId("hunt-attack"), Math.min(r.getDeadlineGameTime(), now + HUNT_TICKS),
                List.of(target.getId()), false, true);
        return startHuntChild(need, attack, HuntChildStage.ATTACK, target.getUUID(),
                "hunt one loaded unprotected "
                        + BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()));
    }

    private TaskState tickActiveChild() {
        // The root fact is checked by onTick before this method. Check the active recursive need too:
        // an external pickup or the child's previous effect may have completed it already.
        if (count(activeNeed.itemIds) >= activeNeed.requiredFinalCount) {
            Need satisfiedNeed = activeNeed;
            cancelActiveBecauseSatisfied();
            if (!needs.isEmpty() && needs.peek() == satisfiedNeed) needs.pop();
            return TaskState.RUNNING;
        }
        if (activeSource == SemanticAcquireTaskRecord.Source.HUNT
                && activeHuntStage == HuntChildStage.ATTACK
                && activeRecord instanceof AttackTaskRecord attack
                && !attack.entityIds.isEmpty()) {
            Entity liveTarget = player.clientLevel.getEntity(attack.entityIds.getFirst());
            if (liveTarget != null && !liveTarget.isRemoved()
                    && EntitySemanticSafety.otherPlayerNearby(player, liveTarget)) {
                activeChild.stop(player, Task.StopReason.REPLACED);
                TaskResult stopped = activeChild.result(TaskState.CANCELLED);
                int after = count(activeNeed.itemIds);
                int progress = Math.max(0, after - activeBeforeCount);
                recordAttempt(TaskState.CANCELLED, stopped, after, progress, false);
                Need interruptedNeed = activeNeed;
                clearActive();
                addIssue("hunt", "other_player_entered_hunt_area",
                        "another player entered the target area; the strict attack was stopped",
                        Map.of("inventory_progress", progress,
                                "requires_narration", true));
                advanceSource(interruptedNeed);
                return TaskState.RUNNING;
            }
        }

        TaskState terminal;
        if (player.level().getGameTime() >= activeRecord.getDeadlineGameTime()) {
            activeChild.stop(player, Task.StopReason.REPLACED);
            terminal = TaskState.TIMEOUT;
        } else {
            terminal = runChild(activeChild);
            if (terminal == null) return TaskState.RUNNING;
        }
        TaskResult result = activeChild.result(terminal);
        int after = count(activeNeed.itemIds);
        int progress = Math.max(0, after - activeBeforeCount);
        recordAttempt(terminal, result, after, progress, false);

        Need completedNeed = activeNeed;
        SemanticAcquireTaskRecord.Source completedSource = activeSource;
        HuntChildStage completedHuntStage = activeHuntStage;
        UUID completedHuntTarget = activeHuntTarget;
        clearActive();

        if (count(r.itemIds) >= r.count) return TaskState.SUCCESS;
        if (after >= completedNeed.requiredFinalCount) {
            if (!needs.isEmpty() && needs.peek() == completedNeed) needs.pop();
            return TaskState.RUNNING;
        }
        if (completedSource == SemanticAcquireTaskRecord.Source.HUNT) {
            return finishHuntChild(
                    completedNeed, completedHuntStage, completedHuntTarget,
                    terminal, result, progress);
        }
        if (completedSource == SemanticAcquireTaskRecord.Source.MINE
                && result != null && result.data() != null
                && "wrong_tool".equals(result.data().get("failure_type"))) {
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
        switch (completedSource) {
            case NEARBY -> advanceSource(completedNeed);
            case CRAFT -> {
                if (progress == 0 || terminal != TaskState.SUCCESS) advanceSource(completedNeed);
            }
            case COOK -> {
                if (progress == 0 || terminal != TaskState.SUCCESS
                        || completedNeed.attempts(completedSource) >= MAX_SOURCE_ATTEMPTS) {
                    advanceSource(completedNeed);
                }
            }
            case STORAGE, MINE -> {
                if (progress == 0
                        || completedNeed.attempts(completedSource) >= MAX_SOURCE_ATTEMPTS) {
                    advanceSource(completedNeed);
                }
            }
            case TRADE -> {
                int attempts = completedNeed.attempts(SemanticAcquireTaskRecord.Source.TRADE);
                int alternatives = Math.min(
                        MAX_TRADE_ALTERNATIVES, completedNeed.itemIds.size());
                if (progress > 0 || attempts >= Math.max(1, alternatives)) {
                    advanceSource(completedNeed);
                }
            }
            default -> advanceSource(completedNeed);
        }
        return TaskState.RUNNING;
    }

    private TaskState finishHuntChild(
            Need need,
            HuntChildStage stage,
            UUID targetUuid,
            TaskState terminal,
            TaskResult result,
            int progress) {
        if (stage == HuntChildStage.SEARCH) {
            if (terminal == TaskState.SUCCESS
                    && result != null && result.success()
                    && bool(result.data() == null ? null : result.data().get("verified"))) {
                // The search receipt intentionally contains no runtime handle. Re-observe the live
                // client world and authorize only a still-alive entity of the hinted type.
                need.huntSearchExpandedView = true;
                return TaskState.RUNNING;
            }
            need.huntSearchExpandedView = false;
            addIssue("hunt", "hunt_entity_search_incomplete",
                    "bounded first-person search ended without a complete acceptable entity observation",
                    Map.of("terminal_state", terminal.name().toLowerCase(),
                            "search_attempts", need.huntSearchAttempts,
                            "search_receipt", result == null || result.data() == null
                                    ? Map.of() : result.data()));
            if (need.huntSearchAttempts >= MAX_HUNT_SEARCHES) advanceSource(need);
            return TaskState.RUNNING;
        }

        if (stage == HuntChildStage.ATTACK) {
            if (terminal == TaskState.SUCCESS || huntDefeated(result)) {
                if (progress == 0) {
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
                }
                if (need.attempts(SemanticAcquireTaskRecord.Source.HUNT)
                        >= huntAttemptLimit(need)) {
                    advanceSource(need);
                }
                // AttackCompanionTask already snapshots pre-existing drops, tracks only new/merged
                // loot from this kill, approaches it and reports the resulting inventory delta.
                // A second type-wide collector here would be able to steal old or unrelated items.
                return TaskState.RUNNING;
            }
            if (targetUuid != null) rejectedHuntTargets.add(targetUuid);
            addIssue("hunt", "hunt_target_lost_or_unreachable",
                    "the selected loaded target disappeared or could not be reached; "
                            + "the next bounded attempt will re-observe loaded entities",
                    Map.of("terminal_state", terminal.name().toLowerCase(),
                            "child_message", result == null || result.message() == null
                                    ? "" : result.message(),
                            "inventory_progress", progress,
                            "attempted_targets", need.attempts(
                                    SemanticAcquireTaskRecord.Source.HUNT)));
            if (need.attempts(SemanticAcquireTaskRecord.Source.HUNT)
                    >= huntAttemptLimit(need)) {
                advanceSource(need);
            }
            return TaskState.RUNNING;
        }

        addIssue("hunt", "hunt_child_stage_missing",
                "a hunt child finished without a retained search/attack stage",
                Map.of("terminal_state", terminal.name().toLowerCase()));
        advanceSource(need);
        return TaskState.RUNNING;
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
        recordAttempt(TaskState.CANCELLED, result, after,
                Math.max(0, after - activeBeforeCount), true);
        clearActive();
    }

    private void clearActive() {
        activeChild = null;
        activeRecord = null;
        activeNeed = null;
        activeSource = null;
        activeDetail = null;
        activeBeforeCount = 0;
        activeHuntStage = HuntChildStage.NONE;
        activeHuntTarget = null;
    }

    private int huntAttemptLimit(Need need) {
        return Math.min(MAX_HUNT_TARGETS, Math.max(3, missing(need)));
    }

    private static boolean huntDefeated(TaskResult result) {
        return result != null && result.data() != null
                && positiveNumber(result.data().get("defeated_targets"));
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
        if (need.parentRecipeId != null) parent.rejectedRecipes.add(need.parentRecipeId);
        addIssue("craft", "recursive_ingredient_unmet",
                "one recipe branch was abandoned after its ingredient need exhausted allowed sources",
                Map.of("recipe_id", need.parentRecipeId == null ? "unknown" : need.parentRecipeId,
                        "ingredient_item_ids", itemStrings(need.itemIds),
                        "required_final_count", need.requiredFinalCount,
                        "observed_final_count", count(need.itemIds)));
        return TaskState.RUNNING;
    }

    private void collectCraftCandidates(
            ResourceLocation output, TaskResult immediate, List<CraftCandidate> target) {
        if (immediate == null || immediate.data() == null) return;
        Object raw = immediate.data().get("candidate_recipes");
        if (!(raw instanceof List<?> values)) return;
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) continue;
            Map<String, Object> data = stringKeyMap(map);
            String recipeId = string(data.get("recipe_id"));
            if (recipeId == null) continue;
            target.add(new CraftCandidate(
                    output,
                    recipeId,
                    data,
                    integer(data.get("ingredients_missing"), Integer.MAX_VALUE),
                    bool(data.get("crafting_surface_supported")),
                    bool(data.get("crafting_surface_ready"))));
        }
    }

    private IngredientNeed chooseIngredient(CraftCandidate candidate, Need parent) {
        Object raw = candidate.data().get("ingredients");
        if (!(raw instanceof List<?> values)) return null;
        List<IngredientNeed> choices = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) continue;
            Map<String, Object> fact = stringKeyMap(map);
            int missing = integer(fact.get("missing"), 0);
            if (missing <= 0) continue;
            List<ResourceLocation> ids = new ArrayList<>();
            Object acceptable = fact.get("acceptable_item_ids");
            if (acceptable instanceof List<?> list) {
                for (Object element : list) {
                    ResourceLocation id = ResourceLocation.tryParse(String.valueOf(element));
                    if (id != null && BuiltInRegistries.ITEM.containsKey(id)
                            && !parent.lineageItems.contains(id)) ids.add(id);
                }
            }
            ids = ids.stream().distinct().toList();
            if (!ids.isEmpty()) choices.add(new IngredientNeed(ids, missing, fact));
        }
        return choices.stream()
                .sorted(Comparator.comparingInt(IngredientNeed::missing))
                .findFirst().orElse(null);
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

    private List<String> worldSourceProtectionProblems(int reach) {
        List<String> result = new ArrayList<>();
        IntentRuntime runtime = IntentRuntime.get();
        for (String label : r.protectedLabels) {
            if (runtime.landmark(label) == null) result.add("unknown protected label: " + label);
        }
        String dimension = player.level().dimension().location().toString();
        BlockPos feet = player.blockPosition();
        for (IntentRuntime.Landmark landmark : runtime.landmarks()) {
            Goal.WorldPosition position = landmark.position();
            if (position.dimension() != null && !position.dimension().equals(dimension)) continue;
            long dx = (long) position.x() - feet.getX();
            long dz = (long) position.z() - feet.getZ();
            if (dx * dx + dz * dz <= (long) reach * reach) {
                result.add("remembered landmark inside child search scope: " + landmark.label());
            }
        }
        return List.copyOf(result);
    }

    private List<String> landmarkReasons(BlockPos position) {
        List<String> result = new ArrayList<>();
        IntentRuntime runtime = IntentRuntime.get();
        String dimension = player.level().dimension().location().toString();
        for (String label : r.protectedLabels) {
            IntentRuntime.Landmark landmark = runtime.landmark(label);
            if (landmark == null) {
                result.add("unknown_protected_label:" + label);
                continue;
            }
            if (insideLandmark(position, landmark, dimension)) {
                result.add("protected_landmark:" + landmark.label());
            }
        }
        for (IntentRuntime.Landmark landmark : runtime.landmarks()) {
            if (insideLandmark(position, landmark, dimension)) {
                result.add("remembered_landmark:" + landmark.label());
            }
        }
        return result.stream().distinct().toList();
    }

    private static boolean insideLandmark(
            BlockPos position, IntentRuntime.Landmark landmark, String dimension) {
        Goal.WorldPosition center = landmark.position();
        if (center.dimension() != null && !center.dimension().equals(dimension)) return false;
        long dx = (long) position.getX() - center.x();
        long dz = (long) position.getZ() - center.z();
        return dx * dx + dz * dz
                <= (long) LANDMARK_PROTECTION_RADIUS * LANDMARK_PROTECTION_RADIUS;
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

    private boolean spendWork() {
        if (workUsed >= r.workBudget) return false;
        workUsed++;
        return true;
    }

    private void advanceSource(Need need) {
        need.sourceCursor++;
    }

    private String childId(String kind) {
        String parent = r.getToolCallId() == null ? "acquire" : r.getToolCallId();
        return parent + "-internal-" + kind + '-' + (++childSerial);
    }

    private TaskState failAcquisition(String code, String message, FailureType type) {
        failureCode = code;
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
                            + "same inventory outcome; MaiCraft will run another bounded first-person search.",
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
        List<Map<String, Object>> bounded = new ArrayList<>(
                options.stream().limit(7).toList());
        bounded.add(stop);
        return List.copyOf(bounded);
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
        data.put("work_budget", r.workBudget);
        data.put("work_used", workUsed);
        data.put("max_recipe_depth", r.maxRecipeDepth);
        data.put("attempts", List.copyOf(attempts));
        data.put("recipe_trace", List.copyOf(recipeTrace));
        data.put("issues", List.copyOf(issues));
        data.put("outcome_uncertain", outcomeUncertain);
        if (failureCode != null) {
            data.put("failure_code", failureCode);
            data.put("requires_decision", true);
            data.put("requires_narration", issues.stream().anyMatch(issue -> {
                Object code = issue.get("code");
                return "harm_permission_required".equals(code)
                        || "protected_hunt_target_requires_decision".equals(code)
                        || "other_player_near_hunt_target".equals(code)
                        || "other_player_entered_hunt_area".equals(code)
                        || "hunt_entity_search_exhausted".equals(code)
                        || "hunt_entity_search_incomplete".equals(code)
                        || "expected_hunt_drop_not_observed".equals(code)
                        || "drop_ownership_ambiguous".equals(code);
            }));
            data.put("recovery_options", recoveryOptions());
        }
        return data;
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
                    "the bounded deadline elapsed before the final inventory fact was true",
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
            if (childResult != null && childResult.data() != null
                    && bool(childResult.data().get("outcome_uncertain"))) {
                outcomeUncertain = true;
            }
            recordAttempt(TaskState.CANCELLED, childResult, count(activeNeed.itemIds),
                    Math.max(0, count(activeNeed.itemIds) - activeBeforeCount), false);
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
