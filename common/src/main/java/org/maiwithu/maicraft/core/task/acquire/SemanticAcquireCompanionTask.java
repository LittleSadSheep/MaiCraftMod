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
